import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:iptv_native/iptv_native.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:webview_flutter/webview_flutter.dart';

import 'browser_sites.dart';

class HistoryEntry {
  const HistoryEntry(this.url, this.title, this.time);
  final String url;
  final String title;
  final DateTime time;

  Map<String, Object?> toJson() => {'u': url, 't': title, 'at': time.millisecondsSinceEpoch};
  static HistoryEntry? fromJson(Object? j) {
    if (j is! Map || j['u'] is! String) return null;
    return HistoryEntry(j['u'] as String, (j['t'] as String?) ?? '',
        DateTime.fromMillisecondsSinceEpoch((j['at'] as num?)?.toInt() ?? 0));
  }
}

/// Owns the phone's single WebView and everything around it: navigation state, history, and the
/// "Send to Car" link. The UI (`BrowserScreen`) only renders this and calls its methods, so the
/// WebView is created once and survives tab switches (no duplicate browser instances).
class BrowserController extends ChangeNotifier {
  BrowserController({IptvNative? native, this.carConnectedProbe})
      : _native = native ?? IptvNative.instance {
    web = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setNavigationDelegate(NavigationDelegate(
        onNavigationRequest: _onRequest,
        onPageStarted: _onStarted,
        onProgress: (p) {
          progress = p / 100;
          notifyListeners();
        },
        onPageFinished: _onFinished,
        onUrlChange: (c) {
          if (c.url != null) {
            _setUrl(c.url!);
            notifyListeners();
          }
        },
        onWebResourceError: _onError,
      ));
    _carSub = _native.carBrowser.listen((s) {
      carState = s;
      notifyListeners();
    }, onError: (Object _) {});
    _historySub = _native.browserHistoryChanged.listen((_) => _refreshHistory(), onError: (Object _) {});
    _native.currentCarBrowser().then((s) {
      carState = s;
      notifyListeners();
    }).catchError((Object _) {});
    _loadHistory();
  }

  static const _historyKey = 'browser.history';

  final IptvNative _native;
  final bool Function()? carConnectedProbe;
  late final WebViewController web;
  StreamSubscription<CarBrowserState>? _carSub;
  StreamSubscription<void>? _historySub;
  Timer? _mirrorTimer;

  /// false = the home screen (search + popular sites) is showing; the WebView stays alive behind it.
  bool inPage = false;
  String url = '';
  String title = '';
  double progress = 0;
  bool loading = false;
  bool canBack = false;
  bool canForward = false;
  String? error;
  final List<HistoryEntry> history = [];

  CarBrowserState carState = const CarBrowserState();

  /// Keep the car on the same page whenever the phone navigates.
  bool mirrorToCar = false;
  bool _sending = false;
  bool get sending => _sending;

  bool get carConnected => carConnectedProbe?.call() ?? false;

  // ---- navigation -----------------------------------------------------------------------

  /// URL, host name or search words, as typed in the address bar.
  Future<bool> submit(String text) async {
    final target = BrowserInput.resolve(text);
    if (target == null) return false;
    await open(target);
    return true;
  }

  Future<void> open(String target) async {
    final uri = Uri.tryParse(target);
    if (uri == null || !BrowserInput.isWebUrl(target)) return;
    inPage = true;
    error = null;
    url = target;
    title = '';
    progress = 0.05;
    loading = true;
    notifyListeners();
    _record(target, ''); // shows up on the car right away, not only when the page finishes
    await web.loadRequest(uri);
  }

  Future<void> goBack() async {
    if (await web.canGoBack()) {
      error = null;
      await web.goBack();
    } else {
      inPage = false;
      notifyListeners();
    }
  }

  Future<void> goForward() async {
    if (await web.canGoForward()) {
      error = null;
      await web.goForward();
    } else if (!inPage && url.isNotEmpty) {
      inPage = true;
      notifyListeners();
    }
  }

  Future<void> reload() async {
    error = null;
    if (url.isNotEmpty) await web.reload();
    notifyListeners();
  }

  Future<void> stop() async {
    try {
      await web.runJavaScript('window.stop();');
    } catch (_) {}
    loading = false;
    progress = 0;
    notifyListeners();
  }

  /// Back to the page that was open before going Home (the WebView kept it).
  void resumePage() {
    if (url.isEmpty) return;
    inPage = true;
    notifyListeners();
  }

  /// System back inside the Browser tab. Returns true if it was consumed.
  Future<bool> handleBack() async {
    if (!inPage) return false;
    await goBack();
    return true;
  }

  void showHome() {
    inPage = false;
    notifyListeners();
  }

  FutureOr<NavigationDecision> _onRequest(NavigationRequest r) {
    if (BrowserInput.isWebUrl(r.url) || r.url.startsWith('about:') || r.url.startsWith('data:') || r.url.startsWith('blob:')) {
      return NavigationDecision.navigate;
    }
    // tel:, mailto:, intent:, market:, app deep links ... belong to the system, not the page.
    if (r.isMainFrame) unawaited(_native.openExternal(r.url));
    return NavigationDecision.prevent;
  }

  void _onStarted(String u) {
    _setUrl(u);
    error = null;
    loading = true;
    progress = progress < 0.05 ? 0.05 : progress;
    notifyListeners();
  }

  Future<void> _onFinished(String u) async {
    _setUrl(u);
    loading = false;
    progress = 1;
    try {
      title = (await web.getTitle()) ?? '';
      canBack = await web.canGoBack();
      canForward = await web.canGoForward();
    } catch (_) {}
    if (error == null) _record(u, title);
    notifyListeners();
    _scheduleMirror();
  }

  void _setUrl(String u) {
    if (u.isEmpty || u == 'about:blank') return;
    url = u;
    web.canGoBack().then((v) {
      if (v != canBack) {
        canBack = v;
        notifyListeners();
      }
    });
    web.canGoForward().then((v) {
      if (v != canForward) {
        canForward = v;
        notifyListeners();
      }
    });
  }

  void _onError(WebResourceError e) {
    if (e.isForMainFrame == false) return;
    if (e.errorCode == -999 || e.errorCode == -1 && (e.description.contains('ERR_ABORTED'))) return; // cancelled
    loading = false;
    progress = 0;
    error = e.description.isEmpty ? 'The page could not be loaded' : _friendly(e.description);
    notifyListeners();
  }

  String _friendly(String d) {
    if (d.contains('ERR_NAME_NOT_RESOLVED')) return "This site can't be reached (check the address or your connection)";
    if (d.contains('ERR_INTERNET_DISCONNECTED')) return 'No internet connection';
    if (d.contains('ERR_CONNECTION_TIMED_OUT')) return 'The connection timed out';
    if (d.contains('ERR_SSL') || d.contains('CERT')) return 'This site has a security certificate problem';
    return d;
  }

  // ---- history --------------------------------------------------------------------------

  Future<void> _loadHistory() async {
    try {
      // Older builds kept history in shared_preferences (phone only): move it into the shared store once.
      final prefs = await SharedPreferences.getInstance();
      final old = prefs.getString(_historyKey);
      if (old != null) {
        final items = <Map<String, Object?>>[];
        for (final j in jsonDecode(old) as List) {
          final h = HistoryEntry.fromJson(j);
          if (h != null) items.add(h.toJson());
        }
        await _native.importBrowserHistory(items);
        await prefs.remove(_historyKey);
      }
    } catch (_) {}
    await _refreshHistory();
  }

  Future<void> _refreshHistory() async {
    try {
      final list = await _native.browserHistory();
      history
        ..clear()
        ..addAll([for (final j in list) ?HistoryEntry.fromJson(j)]);
      notifyListeners();
    } catch (_) {}
  }

  /// Written to the shared store; the change event brings the list back, so phone and car stay equal.
  void _record(String u, String t) {
    if (!BrowserInput.isWebUrl(u)) return;
    _native.recordBrowserHistory(u, t);
  }

  Future<void> clearHistory() async {
    history.clear();
    notifyListeners();
    await _native.clearBrowserHistory();
  }

  // ---- Send to Car ----------------------------------------------------------------------

  /// Sends the page that is on screen right now (read from the WebView, not a stale copy).
  Future<SendToCarResult> sendToCar() async {
    if (_sending) return SendToCarResult.duplicate;
    _sending = true;
    notifyListeners();
    try {
      final live = await web.currentUrl();
      final target = (live != null && BrowserInput.isWebUrl(live)) ? live : url;
      if (!BrowserInput.isWebUrl(target)) return SendToCarResult.invalid;
      return await _native.sendBrowserUrl(target);
    } finally {
      _sending = false;
      notifyListeners();
    }
  }

  void setMirror(bool on) {
    mirrorToCar = on;
    notifyListeners();
    if (on) _scheduleMirror();
  }

  String _lastMirrored = '';
  void _scheduleMirror() {
    if (!mirrorToCar || !inPage || !BrowserInput.isWebUrl(url) || url == _lastMirrored) return;
    _mirrorTimer?.cancel();
    _mirrorTimer = Timer(const Duration(milliseconds: 800), () async {
      final u = url;
      if (u == _lastMirrored || !BrowserInput.isWebUrl(u)) return;
      final r = await _native.sendBrowserUrl(u);
      if (r == SendToCarResult.sent || r == SendToCarResult.queued || r == SendToCarResult.duplicate) _lastMirrored = u;
    });
  }

  @override
  void dispose() {
    _carSub?.cancel();
    _historySub?.cancel();
    _mirrorTimer?.cancel();
    super.dispose();
  }
}

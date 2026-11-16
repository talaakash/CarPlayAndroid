import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:iptv_native/iptv_native.dart';

/// Keeps the browsing selection (All / Favorites / Recent / a category) in sync between the
/// phone and the car's "Phone" list, in both directions, without echo:
///  * phone -> car: [selectFromPhone] (a no-op if the scope is already selected)
///  * car -> phone: native emits origin=car; the UI applies it through [remoteSelection]
///    and does NOT send it back.
class AutomotiveNavigationService {
  AutomotiveNavigationService(this._native);
  final IptvNative _native;
  StreamSubscription<BrowseSelection>? _sub;

  int _lastVersion = -1;
  String _scope = 'all';
  String get scope => _scope;

  /// Set when the car changed the selection. The UI listens and applies it locally.
  final ValueNotifier<BrowseSelection?> remoteSelection = ValueNotifier(null);

  void start() {
    _sub = _native.browseSelection.listen(handle);
    _native.currentSelection().then((s) {
      _lastVersion = s.version;
      _scope = s.scope;
    }).catchError((Object _) {});
  }

  /// Visible for tests.
  @visibleForTesting
  void handle(BrowseSelection s) {
    if (s.version <= _lastVersion) return; // stale or duplicate
    _lastVersion = s.version;
    _scope = s.scope;
    if (s.origin == CommandOrigin.car) remoteSelection.value = s;
    // origin == phone is our own change coming back: ignore (loop prevention).
  }

  Future<void> selectFromPhone(String scope, String label) async {
    if (scope == _scope) return;
    _scope = scope;
    await _native.setSelection(scope, label);
  }

  void dispose() {
    _sub?.cancel();
    remoteSelection.dispose();
  }
}

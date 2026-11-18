import 'package:cached_network_image/cached_network_image.dart';
import 'package:iptv_native/iptv_native.dart';
import 'package:material_ui/material_ui.dart';
import 'package:webview_flutter/webview_flutter.dart';

import 'browser_controller.dart';
import 'browser_sites.dart';

/// The Browser tab: home (address bar + popular sites) and the live WebView with its toolbar.
/// All state lives in [BrowserController]; this widget only renders it.
class BrowserScreen extends StatefulWidget {
  const BrowserScreen({super.key, required this.browser});
  final BrowserController browser;

  @override
  State<BrowserScreen> createState() => _BrowserScreenState();
}

class _BrowserScreenState extends State<BrowserScreen> {
  final _field = TextEditingController();
  final _focus = FocusNode();
  String _shownUrl = '';

  BrowserController get b => widget.browser;

  @override
  void initState() {
    super.initState();
    b.addListener(_syncField);
  }

  @override
  void dispose() {
    b.removeListener(_syncField);
    _field.dispose();
    _focus.dispose();
    super.dispose();
  }

  /// Mirror the current URL in the address bar unless the user is typing.
  void _syncField() {
    if (_focus.hasFocus || b.url == _shownUrl) return;
    _shownUrl = b.url;
    _field.text = b.inPage ? b.url : '';
  }

  Future<void> _submit(String text) async {
    _focus.unfocus();
    final ok = await b.submit(text);
    if (ok) {
      _shownUrl = '';
      _syncField();
    }
  }

  Future<void> _send() async {
    final m = ScaffoldMessenger.of(context);
    final r = await b.sendToCar();
    final (msg, icon) = switch (r) {
      SendToCarResult.sent => ('Opened on the car display', Icons.check_circle),
      SendToCarResult.unconfirmed => ('Sent, but the car has not shown it. Open this app on the car display and try again.', Icons.warning_amber),
      SendToCarResult.queued => ('Car connected – open the app on the car display to see it', Icons.directions_car),
      SendToCarResult.duplicate => ('Already open on the car', Icons.info_outline),
      SendToCarResult.noCar => ('No car display connected. Connect Android Auto and try again.', Icons.link_off),
      SendToCarResult.invalid => ('Only web pages (http/https) can be sent to the car', Icons.error_outline),
    };
    m
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Row(children: [Icon(icon, size: 20), const SizedBox(width: 12), Expanded(child: Text(msg))])));
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: b,
      builder: (context, _) => Column(children: [
        _addressBar(context),
        Expanded(
          child: IndexedStack(index: b.inPage ? 1 : 0, children: [
            _home(context),
            Stack(children: [
              Positioned.fill(child: WebViewWidget(controller: b.web)),
              if (b.error != null) Positioned.fill(child: _errorView(context)),
            ]),
          ]),
        ),
        if (b.inPage) _toolbar(context),
      ]),
    );
  }

  // ---- address bar -----------------------------------------------------------------------

  Widget _addressBar(BuildContext context) {
    final t = Theme.of(context);
    final scheme = t.colorScheme;
    return Material(
      color: scheme.surface,
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            if (b.inPage && b.title.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(left: 6, bottom: 4),
                child: Text(b.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: t.textTheme.labelLarge),
              ),
            TextField(
              controller: _field,
              focusNode: _focus,
              keyboardType: TextInputType.url,
              textInputAction: TextInputAction.go,
              autocorrect: false,
              enableSuggestions: false,
              onSubmitted: _submit,
              onChanged: (_) => setState(() {}),
              onTap: () => _field.selection = TextSelection(baseOffset: 0, extentOffset: _field.text.length),
              decoration: InputDecoration(
                hintText: 'Search Google or type a URL',
                prefixIcon: Icon(!b.inPage
                    ? Icons.search
                    : b.url.startsWith('https://')
                        ? Icons.lock_outline
                        : Icons.public),
                suffixIcon: _field.text.isNotEmpty
                    ? IconButton(
                        tooltip: 'Clear',
                        icon: const Icon(Icons.close),
                        onPressed: () {
                          _field.clear();
                          setState(() {});
                          _focus.requestFocus();
                        },
                      )
                    : null,
                filled: true,
                fillColor: scheme.surfaceContainerHigh,
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(vertical: 12),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(28), borderSide: BorderSide.none),
              ),
            ),
          ]),
        ),
        SizedBox(
          height: 3,
          child: b.loading ? LinearProgressIndicator(value: b.progress > 0 && b.progress < 1 ? b.progress : null) : null,
        ),
      ]),
    );
  }

  // ---- home --------------------------------------------------------------------------------

  Widget _home(BuildContext context) {
    final t = Theme.of(context);
    final scheme = t.colorScheme;
    return ListView(padding: const EdgeInsets.fromLTRB(16, 16, 16, 24), children: [
      if (b.carState.open)
        Card(
          color: scheme.secondaryContainer,
          margin: const EdgeInsets.only(bottom: 16),
          child: ListTile(
            leading: const Icon(Icons.directions_car),
            title: Text('On the car: ${b.carState.title.isEmpty ? b.carState.url : b.carState.title}',
                maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text(b.carState.loading ? 'Loading…' : b.carState.url, maxLines: 1, overflow: TextOverflow.ellipsis),
            onTap: () => b.open(b.carState.url),
          ),
        ),
      if (b.url.isNotEmpty)
        Card(
          margin: const EdgeInsets.only(bottom: 16),
          child: ListTile(
            leading: const Icon(Icons.language),
            title: Text(b.title.isEmpty ? b.url : b.title, maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text(b.url, maxLines: 1, overflow: TextOverflow.ellipsis),
            trailing: FilledButton.tonalIcon(onPressed: b.sending ? null : _send, icon: const Icon(Icons.directions_car), label: const Text('Send')),
            onTap: b.resumePage,
          ),
        ),
      Text('Popular sites', style: t.textTheme.titleMedium),
      const SizedBox(height: 12),
      LayoutBuilder(builder: (context, c) {
        final cols = c.maxWidth >= 600 ? 6 : 4;
        return GridView.count(
          crossAxisCount: cols,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: 12,
          crossAxisSpacing: 8,
          childAspectRatio: 0.82,
          children: [for (final s in popularSites) _SiteTile(site: s, onTap: () => b.open(s.url))],
        );
      }),
      if (b.history.isNotEmpty) ...[
        const SizedBox(height: 20),
        Row(children: [
          Expanded(child: Text('Recently visited', style: t.textTheme.titleMedium)),
          TextButton(onPressed: b.clearHistory, child: const Text('Clear')),
        ]),
        for (final h in b.history.take(6))
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.history),
            title: Text(h.title.isEmpty ? h.url : h.title, maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text(h.url, maxLines: 1, overflow: TextOverflow.ellipsis),
            onTap: () => b.open(h.url),
          ),
      ],
    ]);
  }

  // ---- page chrome -------------------------------------------------------------------------

  Widget _errorView(BuildContext context) {
    final t = Theme.of(context);
    return ColoredBox(
      color: t.colorScheme.surface,
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Icon(Icons.cloud_off, size: 56, color: t.colorScheme.primary),
            const SizedBox(height: 12),
            Text("Can't open this page", style: t.textTheme.titleMedium),
            const SizedBox(height: 4),
            Text(b.error!, textAlign: TextAlign.center, style: t.textTheme.bodyMedium),
            const SizedBox(height: 4),
            Text(b.url, maxLines: 2, overflow: TextOverflow.ellipsis, style: t.textTheme.bodySmall),
            const SizedBox(height: 16),
            Wrap(spacing: 8, children: [
              FilledButton.icon(onPressed: b.reload, icon: const Icon(Icons.refresh), label: const Text('Try again')),
              OutlinedButton(onPressed: b.showHome, child: const Text('Home')),
            ]),
          ]),
        ),
      ),
    );
  }

  Widget _toolbar(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Material(
      color: scheme.surfaceContainer,
      child: SafeArea(
        top: false,
        child: SizedBox(
          height: 56,
          child: Row(children: [
            IconButton(tooltip: 'Back', icon: const Icon(Icons.arrow_back), onPressed: b.goBack),
            IconButton(tooltip: 'Forward', icon: const Icon(Icons.arrow_forward), onPressed: b.canForward ? b.goForward : null),
            IconButton(
              tooltip: b.loading ? 'Stop' : 'Reload',
              icon: Icon(b.loading ? Icons.close : Icons.refresh),
              onPressed: b.loading ? b.stop : b.reload,
            ),
            IconButton(tooltip: 'Home', icon: const Icon(Icons.home_outlined), onPressed: b.showHome),
            const Spacer(),
            FilledButton.icon(
              onPressed: b.sending ? null : _send,
              icon: b.sending
                  ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.directions_car),
              label: const Text('Send to Car'),
            ),
            PopupMenuButton<String>(
              tooltip: 'More',
              onSelected: (v) {
                if (v == 'mirror') b.setMirror(!b.mirrorToCar);
                if (v == 'history') _showHistory(context);
              },
              itemBuilder: (_) => [
                CheckedPopupMenuItem(value: 'mirror', checked: b.mirrorToCar, child: const Text('Keep car in sync')),
                const PopupMenuItem(value: 'history', child: Text('History')),
              ],
            ),
          ]),
        ),
      ),
    );
  }

  void _showHistory(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (ctx) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.6,
        builder: (ctx, scroll) => ListenableBuilder(
          listenable: b,
          builder: (ctx, _) => Column(children: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Row(children: [
                Expanded(child: Text('History', style: Theme.of(ctx).textTheme.titleLarge)),
                TextButton(onPressed: b.history.isEmpty ? null : b.clearHistory, child: const Text('Clear all')),
              ]),
            ),
            Expanded(
              child: b.history.isEmpty
                  ? const Center(child: Text('No history yet'))
                  : ListView.builder(
                      controller: scroll,
                      itemCount: b.history.length,
                      itemBuilder: (_, i) {
                        final h = b.history[i];
                        return ListTile(
                          leading: const Icon(Icons.history),
                          title: Text(h.title.isEmpty ? h.url : h.title, maxLines: 1, overflow: TextOverflow.ellipsis),
                          subtitle: Text(h.url, maxLines: 1, overflow: TextOverflow.ellipsis),
                          onTap: () {
                            Navigator.pop(ctx);
                            b.open(h.url);
                          },
                        );
                      },
                    ),
            ),
          ]),
        ),
      ),
    );
  }
}

class _SiteTile extends StatelessWidget {
  const _SiteTile({required this.site, required this.onTap});
  final PopularSite site;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final t = Theme.of(context);
    final fallback = ColoredBox(
      color: site.color,
      child: Center(child: Text(site.name[0], style: const TextStyle(color: Colors.white, fontSize: 26, fontWeight: FontWeight.bold))),
    );
    return InkWell(
      borderRadius: BorderRadius.circular(16),
      onTap: onTap,
      child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
        Container(
          width: 60,
          height: 60,
          clipBehavior: Clip.antiAlias,
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(16),
            boxShadow: const [BoxShadow(color: Colors.black12, blurRadius: 4, offset: Offset(0, 1))],
          ),
          child: CachedNetworkImage(
            imageUrl: faviconUrl(site.domain),
            fit: BoxFit.contain,
            width: 60,
            height: 60,
            imageBuilder: (_, img) => Padding(padding: const EdgeInsets.all(12), child: Image(image: img)),
            placeholder: (_, _) => fallback,
            errorWidget: (_, _, _) => fallback,
          ),
        ),
        const SizedBox(height: 6),
        Text(site.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: t.textTheme.labelMedium),
      ]),
    );
  }
}

import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import 'browser/browser_controller.dart';
import 'browser/browser_screen.dart';
import 'iptv/automotive/car_connection_service.dart';
import 'iptv/iptv_repository.dart';
import 'iptv/ui/iptv_home.dart';
import 'iptv/ui/local_videos_screen.dart';
import 'iptv/ui/mini_player.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  IPTVRepository? _repo;

  @override
  void initState() {
    super.initState();
    // The IPTV core (shared with Android Auto / CarPlay) starts with the app so the
    // playlist is cached for the car even before the user touches anything.
    // Unavailable platforms (e.g. widget tests) simply run without IPTV.
    IPTVRepository.create().then((repo) {
      repo.start();
      if (mounted) setState(() => _repo = repo);
    }).catchError((Object _) {});
  }

  @override
  Widget build(BuildContext context) {
    final repo = _repo;
    if (repo == null) return _app(Colors.deepPurple, Brightness.light);

    return ChangeNotifierProvider<IPTVRepository>.value(
      value: repo,
      child: ListenableBuilder(
        listenable: repo.settings,
        builder: (context, _) => _app(
          repo.settings.accentColor,
          repo.settings.isDark ? Brightness.dark : Brightness.light,
        ),
      ),
    );
  }

  Widget _app(Color seed, Brightness brightness) {
    return MaterialApp(
      title: 'Car Play',
      theme: ThemeData(
        colorScheme: .fromSeed(seedColor: seed, brightness: brightness),
      ),
      home: const MyHomePage(title: 'Car Play'),
    );
  }
}

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key, required this.title});

  final String title;

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

/// Home shell: bottom tab bar with IPTV, Local Video and Browser. Tabs are kept alive in an
/// IndexedStack so switching never restarts a list, the player or the WebView; the playback
/// engine itself is native and unaffected by tab changes.
class _MyHomePageState extends State<MyHomePage> {
  static const _iptv = 0, _local = 1, _browserTab = 2;
  int _tab = _iptv;
  BrowserController? _browser;

  BrowserController _browserFor(IPTVRepository? repo) => _browser ??= BrowserController(
        carConnectedProbe: () {
          final s = repo?.automotive.connection.status;
          return s != null && s != CarLinkStatus.disconnected && s != CarLinkStatus.checking;
        },
      );

  @override
  void dispose() {
    _browser?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final repo = context.watch<IPTVRepository?>();
    final browser = _tab == _browserTab || _browser != null ? _browserFor(repo) : null;

    return ListenableBuilder(
      listenable: Listenable.merge([?browser]),
      builder: (context, _) => PopScope(
        // In the Browser tab the system Back button navigates the page first.
        canPop: !(_tab == _browserTab && (browser?.inPage ?? false)),
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop) browser?.handleBack();
        },
        child: Scaffold(
          appBar: _tab == _browserTab ? null : _appBar(context, repo),
          bottomNavigationBar: Column(mainAxisSize: MainAxisSize.min, children: [
            if (repo != null && _tab != _browserTab) MiniPlayer(repo: repo),
            NavigationBar(
              selectedIndex: _tab,
              onDestinationSelected: (i) => setState(() => _tab = i),
              destinations: const [
                NavigationDestination(icon: Icon(Icons.live_tv_outlined), selectedIcon: Icon(Icons.live_tv), label: 'IPTV'),
                NavigationDestination(icon: Icon(Icons.video_library_outlined), selectedIcon: Icon(Icons.video_library), label: 'Local Video'),
                NavigationDestination(icon: Icon(Icons.public), selectedIcon: Icon(Icons.public), label: 'Browser'),
              ],
            ),
          ]),
          body: IndexedStack(index: _tab, children: [
            repo == null
                ? const Center(child: Text('IPTV is starting or not available on this platform.'))
                : IptvHomeBody(repo: repo),
            repo == null
                ? const Center(child: Text('Local video is not available on this platform.'))
                : LocalVideosScreen(repo: repo, embedded: true),
            // Created on first visit only: no WebView exists until the user opens the tab.
            if (browser != null) SafeArea(bottom: false, child: BrowserScreen(browser: browser)) else const SizedBox.shrink(),
          ]),
        ),
      ),
    );
  }

  AppBar _appBar(BuildContext context, IPTVRepository? repo) {
    final isLocal = _tab == _local;
    return AppBar(
      backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      leading: Icon(isLocal ? Icons.video_library : Icons.live_tv),
      title: Column(
        crossAxisAlignment: .start,
        children: [
          Text(widget.title),
          if (repo != null && !isLocal)
            ListenableBuilder(
              listenable: repo,
              builder: (_, _) => Text(iptvStatusLine(repo), style: Theme.of(context).textTheme.bodySmall),
            ),
          if (isLocal) Text('Local videos', style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
      actions: [
        if (repo != null && !isLocal) ...iptvAppBarActions(context, repo),
        if (repo != null && isLocal)
          IconButton(tooltip: 'Add videos', icon: const Icon(Icons.add), onPressed: () => LocalVideosScreen.addVideos(context, repo)),
      ],
    );
  }
}

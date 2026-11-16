import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import 'iptv/iptv_repository.dart';
import 'iptv/ui/iptv_home.dart';
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

class _MyHomePageState extends State<MyHomePage> {

  @override
  Widget build(BuildContext context) {
    final repo = context.watch<IPTVRepository?>();

    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        leading: const Icon(Icons.live_tv),
        title: Column(
          crossAxisAlignment: .start,
          children: [
            Text(widget.title),
            if (repo != null)
              ListenableBuilder(
                listenable: repo,
                builder: (_, _) => Text(iptvStatusLine(repo), style: Theme.of(context).textTheme.bodySmall),
              ),
          ],
        ),
        actions: [if (repo != null) ...iptvAppBarActions(context, repo)],
      ),
      bottomNavigationBar: repo == null ? null : MiniPlayer(repo: repo),
      body: Column(
        children: [
          Expanded(
            child: repo == null
                ? const Center(child: Text('IPTV is starting or not available on this platform.'))
                : IptvHomeBody(repo: repo),
          ),
        ],
      ),
    );
  }
}

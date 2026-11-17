import 'package:iptv_native/iptv_native.dart';
import 'package:material_ui/material_ui.dart';

import '../automotive/car_connection_service.dart';
import '../iptv_repository.dart';
import '../widgets/channel_tile.dart';
import 'fullscreen_player.dart';
import 'video_view.dart';

/// Full-screen player. One shared engine plays the stream; this screen decides only what the
/// PHONE shows:
///  * output = Phone: the video (Fit / Fill), while Android Auto shows artwork + controls.
///  * output = Android Auto: artwork + controls here, while the car shows the video.
/// The output and Fit/Fill choices are stored natively, so the car reads the same values.
class PlayerScreen extends StatefulWidget {
  const PlayerScreen({super.key, required this.repo});
  final IPTVRepository repo;

  static const artworkHero = 'player-artwork';

  static Future<void> open(BuildContext context, IPTVRepository repo) {
    return Navigator.of(context).push(PageRouteBuilder<void>(
      pageBuilder: (_, _, _) => PlayerScreen(repo: repo),
      transitionDuration: const Duration(milliseconds: 250),
      reverseTransitionDuration: const Duration(milliseconds: 200),
      transitionsBuilder: (_, anim, _, child) => SlideTransition(
        position: Tween(begin: const Offset(0, 1), end: Offset.zero)
            .animate(CurvedAnimation(parent: anim, curve: Curves.easeOutCubic)),
        child: child,
      ),
    ));
  }

  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> {
  bool _pipSupported = false;
  bool _closing = false;
  // While the full-screen route is open it owns the one video view.
  bool _fullscreen = false;

  IPTVRepository get repo => widget.repo;

  @override
  void initState() {
    super.initState();
    repo.playback.addListener(_syncPip);
    repo.prefs.addListener(_syncPip);
    repo.automotive.addListener(_syncPip);
    repo.settings.addListener(_syncPip);
    repo.player.pipSupported().then((v) {
      if (mounted) setState(() => _pipSupported = v);
    }).catchError((_) {});
    _syncPip();
  }

  @override
  void dispose() {
    repo.playback.removeListener(_syncPip);
    repo.prefs.removeListener(_syncPip);
    repo.automotive.removeListener(_syncPip);
    repo.settings.removeListener(_syncPip);
    // The video is only visible here, so auto-PiP must not outlive this screen.
    repo.player.setAutoPip(false).catchError((_) {});
    super.dispose();
  }

  /// Auto-PiP only while this screen shows live video on the phone.
  void _syncPip() {
    final on = repo.playback.isPlaying && repo.settings.autoPip && !repo.videoOnCar;
    repo.player.setAutoPip(on).catchError((_) {});
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([repo, repo.playback, repo.favorites, repo.prefs, repo.automotive, repo.parked]),
      builder: (context, _) {
        final pc = repo.playback;
        if ((pc.current == null || !pc.hasMedia) && !_closing) {
          // Stopped from anywhere (e.g. the car): nothing left to show.
          _closing = true;
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted) Navigator.of(context).maybePop();
          });
        }
        final landscape = MediaQuery.orientationOf(context) == Orientation.landscape;
        final inPip = pc.state.inPictureInPicture;
        return Scaffold(
          backgroundColor: Theme.of(context).colorScheme.surface,
          appBar: inPip
              ? null
              : AppBar(
                  leading: IconButton(
                    tooltip: 'Minimize',
                    icon: const Icon(Icons.keyboard_arrow_down, size: 32),
                    onPressed: () => Navigator.of(context).maybePop(),
                  ),
                  title: const Text('Now playing'),
                  centerTitle: true,
                ),
          body: SafeArea(
            child: inPip
                ? _stage()
                : landscape
                    ? Row(children: [
                        Expanded(flex: 3, child: _stage()),
                        Expanded(flex: 2, child: SingleChildScrollView(child: _panel())),
                      ])
                    : SingleChildScrollView(
                        child: Column(children: [
                          AspectRatio(aspectRatio: 16 / 9, child: _stage()),
                          _panel(),
                        ]),
                      ),
          ),
        );
      },
    );
  }

  // ---- stage: video or artwork --------------------------------------------------

  Widget _stage() {
    final pc = repo.playback;
    final parked = repo.parked.info;
    final onCar = repo.videoOnCar;

    if (onCar) {
      return _artworkStage(
        'Playing on Android Auto',
        'The video is on the car display. Controls here and in the car stay in sync.',
        icon: Icons.directions_car,
      );
    }
    if (!parked.videoAllowed) {
      return _artworkStage(
        parked.playbackBlocked ? 'Playback paused while driving' : 'Audio only while driving',
        'Video returns when the vehicle is parked.',
        icon: Icons.headphones,
        logo: false,
      );
    }
    final fill = repo.prefs.fit == VideoFit.fill;
    return ColoredBox(
      color: Colors.black,
      child: Stack(fit: StackFit.expand, children: [
        // Fit / Fill is applied natively to the video surface itself (see IptvNativePlugin.applyFit),
        // so the view simply fills the stage.
        if (_fullscreen) const SizedBox.shrink() else const NativeVideoView(),
        if (pc.isBuffering) const Center(child: CircularProgressIndicator(color: Colors.white)),
        if (pc.hasError)
          ColoredBox(
            color: Colors.black54,
            child: Center(
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                const Icon(Icons.error_outline, color: Colors.redAccent, size: 40),
                const Text('This channel cannot be played right now.', style: TextStyle(color: Colors.white70)),
                TextButton(onPressed: pc.retry, child: const Text('Retry')),
              ]),
            ),
          ),
        if (!pc.state.inPictureInPicture)
          Positioned(
            top: 4,
            right: 4,
            child: Row(children: [
              IconButton(
                tooltip: fill ? 'Switch to Fit' : 'Switch to Fill',
                color: Colors.white,
                icon: Icon(fill ? Icons.fit_screen : Icons.crop_free),
                onPressed: () => repo.prefs.setFit(fill ? VideoFit.fit : VideoFit.fill),
              ),
              IconButton(
                tooltip: 'Full screen',
                color: Colors.white,
                icon: const Icon(Icons.fullscreen),
                onPressed: () async {
                  setState(() => _fullscreen = true);
                  await WidgetsBinding.instance.endOfFrame;
                  if (!mounted) return;
                  await FullscreenPlayer.open(context, repo);
                  if (mounted) setState(() => _fullscreen = false);
                },
              ),
              if (_pipSupported)
                IconButton(
                  tooltip: 'Picture in picture',
                  color: Colors.white,
                  icon: const Icon(Icons.picture_in_picture_alt),
                  onPressed: repo.player.enterPip,
                ),
            ]),
          ),
      ]),
    );
  }

  Widget _artworkStage(String title, String subtitle, {required IconData icon, bool logo = true}) {
    final ch = repo.playback.current;
    final pc = repo.playback;
    // FittedBox scales the content down when the stage is short (small phones, landscape),
    // so it can never overflow.
    return ColoredBox(
      color: Colors.black,
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: FittedBox(
            fit: BoxFit.scaleDown,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 320),
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                if (logo && ch != null)
                  Hero(tag: PlayerScreen.artworkHero, child: ChannelLogo(url: ch.logo, size: 96))
                else
                  Icon(icon, color: Colors.white70, size: 56),
                const SizedBox(height: 10),
                Text(title, textAlign: TextAlign.center, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600)),
                const SizedBox(height: 2),
                Text(subtitle, textAlign: TextAlign.center, style: const TextStyle(color: Colors.white54, fontSize: 12)),
                if (pc.hasError) TextButton(onPressed: pc.retry, child: const Text('Retry')),
              ]),
            ),
          ),
        ),
      ),
    );
  }

  // ---- info, controls, output selector --------------------------------------------

  Widget _panel() {
    final pc = repo.playback;
    final ch = pc.current;
    if (ch == null) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final parked = repo.parked.info;
    final big = parked.automotive;
    final target = big ? 64.0 : 48.0;
    final gap = big ? 24.0 : 12.0;
    ButtonStyle? sized() => big ? IconButton.styleFrom(minimumSize: Size(target, target)) : null;
    final status = pc.hasError
        ? 'Playback error'
        : pc.isBuffering
            ? 'Buffering…'
            : pc.isPlaying
                ? 'Live'
                : 'Paused';

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(ch.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: theme.textTheme.titleLarge),
              Text('$status · ${ch.group}', maxLines: 1, overflow: TextOverflow.ellipsis, style: theme.textTheme.bodyMedium),
            ]),
          ),
          IconButton(
            tooltip: 'Favorite',
            iconSize: 28,
            icon: Icon(repo.favorites.isFavorite(ch.id) ? Icons.favorite : Icons.favorite_border,
                color: repo.favorites.isFavorite(ch.id) ? theme.colorScheme.primary : null),
            onPressed: () => repo.toggleFavorite(ch),
          ),
        ]),
        const SizedBox(height: 8),
        Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          IconButton(iconSize: big ? 48 : 40, style: sized(), tooltip: 'Previous channel', icon: const Icon(Icons.skip_previous), onPressed: pc.previous),
          SizedBox(width: gap),
          IconButton.filled(
            iconSize: big ? 48 : 44,
            style: sized(),
            tooltip: pc.isPlaying ? 'Pause' : 'Play',
            icon: Icon(pc.isPlaying ? Icons.pause : Icons.play_arrow),
            // Driving without background audio: play/pause is withdrawn natively too.
            onPressed: parked.playbackBlocked ? null : pc.togglePlay,
          ),
          SizedBox(width: gap),
          IconButton(iconSize: big ? 48 : 40, style: sized(), tooltip: 'Next channel', icon: const Icon(Icons.skip_next), onPressed: pc.next),
        ]),
        const SizedBox(height: 16),
        Text('Play video on', style: theme.textTheme.labelLarge),
        const SizedBox(height: 8),
        SizedBox(
          width: double.infinity,
          child: SegmentedButton<VideoOutput>(
            showSelectedIcon: false,
            segments: const [
              ButtonSegment(value: VideoOutput.phone, icon: Icon(Icons.smartphone), label: Text('Mobile')),
              ButtonSegment(value: VideoOutput.car, icon: Icon(Icons.directions_car), label: Text('Android Auto')),
            ],
            selected: {repo.prefs.output},
            onSelectionChanged: (s) => repo.prefs.setOutput(s.first),
          ),
        ),
        const SizedBox(height: 8),
        Text(_outputHint(), style: theme.textTheme.bodySmall),
      ]),
    );
  }

  String _outputHint() {
    final connected = repo.automotive.connection.status != CarLinkStatus.disconnected &&
        repo.automotive.connection.status != CarLinkStatus.checking;
    if (repo.prefs.output == VideoOutput.phone) {
      return 'Video is on this phone. Android Auto shows the artwork and playback controls.';
    }
    return connected
        ? 'Video is on the car display. This phone shows the artwork and playback controls.'
        : 'No car connected, so the video stays on this phone. It moves to the car display as soon as Android Auto connects.';
  }
}

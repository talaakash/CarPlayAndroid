import 'dart:async';

import 'package:flutter/services.dart';
import 'package:iptv_native/iptv_native.dart';
import 'package:material_ui/material_ui.dart';

import '../iptv_repository.dart';
import '../widgets/local_seek_bar.dart';
import 'video_view.dart';

/// Landscape full-screen video. Reuses the same native player and view as the normal player;
/// only the route chrome differs. Controls fade out after a few seconds and return on tap.
class FullscreenPlayer extends StatefulWidget {
  const FullscreenPlayer({super.key, required this.repo});
  final IPTVRepository repo;

  static Future<void> open(BuildContext context, IPTVRepository repo) {
    return Navigator.of(context).push(PageRouteBuilder<void>(
      opaque: true,
      pageBuilder: (_, _, _) => FullscreenPlayer(repo: repo),
      transitionDuration: const Duration(milliseconds: 200),
      reverseTransitionDuration: const Duration(milliseconds: 200),
      transitionsBuilder: (_, anim, _, child) => FadeTransition(opacity: anim, child: child),
    ));
  }

  @override
  State<FullscreenPlayer> createState() => _FullscreenPlayerState();
}

class _FullscreenPlayerState extends State<FullscreenPlayer> {
  bool _controls = true;
  Timer? _hide;

  IPTVRepository get repo => widget.repo;

  @override
  void initState() {
    super.initState();
    SystemChrome.setPreferredOrientations(
        [DeviceOrientation.landscapeLeft, DeviceOrientation.landscapeRight]);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    _scheduleHide();
  }

  @override
  void dispose() {
    _hide?.cancel();
    // Back to the app's normal behaviour: any orientation, system bars visible.
    SystemChrome.setPreferredOrientations(const []);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    super.dispose();
  }

  void _scheduleHide() {
    _hide?.cancel();
    _hide = Timer(const Duration(seconds: 4), () {
      if (mounted) setState(() => _controls = false);
    });
  }

  void _toggle() {
    setState(() => _controls = !_controls);
    if (_controls) _scheduleHide();
  }

  void _act(VoidCallback f) {
    f();
    _scheduleHide();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([repo, repo.playback, repo.favorites, repo.prefs]),
      builder: (context, _) {
        final pc = repo.playback;
        final ch = pc.current;
        if (ch == null || !pc.hasMedia) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted) Navigator.of(context).maybePop();
          });
        }
        final fill = repo.prefs.fit == VideoFit.fill;
        return Scaffold(
          backgroundColor: Colors.black,
          body: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: _toggle,
            child: Stack(fit: StackFit.expand, children: [
              const NativeVideoView(),
              if (pc.isBuffering) const Center(child: CircularProgressIndicator(color: Colors.white)),
              if (pc.hasError)
                Center(
                  child: Column(mainAxisSize: MainAxisSize.min, children: [
                    const Icon(Icons.error_outline, color: Colors.redAccent, size: 40),
                    const SizedBox(height: 4),
                    Text(pc.isLocal ? 'This video cannot be played.' : 'This channel cannot be played right now.', style: TextStyle(color: Colors.white70)),
                    TextButton(onPressed: pc.retry, child: const Text('Retry')),
                  ]),
                ),
              AnimatedOpacity(
                opacity: _controls ? 1 : 0,
                duration: const Duration(milliseconds: 200),
                child: IgnorePointer(
                  ignoring: !_controls,
                  child: Stack(fit: StackFit.expand, children: [
                    // top scrim + title
                    Positioned(
                      top: 0, left: 0, right: 0,
                      child: DecoratedBox(
                        decoration: const BoxDecoration(
                          gradient: LinearGradient(
                            begin: Alignment.topCenter,
                            end: Alignment.bottomCenter,
                            colors: [Color(0xCC000000), Color(0x00000000)],
                          ),
                        ),
                        child: SafeArea(
                          bottom: false,
                          child: Padding(
                            padding: const EdgeInsets.fromLTRB(4, 4, 12, 24),
                            child: Row(children: [
                              IconButton(
                                tooltip: 'Exit full screen',
                                color: Colors.white,
                                icon: const Icon(Icons.fullscreen_exit, size: 28),
                                onPressed: () => Navigator.of(context).maybePop(),
                              ),
                              Expanded(
                                child: Text(ch?.name ?? '',
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.w600)),
                              ),
                              if (ch != null && !pc.isLocal)
                                IconButton(
                                  tooltip: 'Favorite',
                                  color: Colors.white,
                                  icon: Icon(repo.favorites.isFavorite(ch.id) ? Icons.favorite : Icons.favorite_border),
                                  onPressed: () => _act(() => repo.toggleFavorite(ch)),
                                ),
                              IconButton(
                                tooltip: fill ? 'Switch to Fit' : 'Switch to Fill',
                                color: Colors.white,
                                icon: Icon(fill ? Icons.fit_screen : Icons.crop_free),
                                onPressed: () => _act(() => repo.prefs.setFit(fill ? VideoFit.fit : VideoFit.fill)),
                              ),
                            ]),
                          ),
                        ),
                      ),
                    ),
                    // bottom scrim + transport
                    Positioned(
                      bottom: 0, left: 0, right: 0,
                      child: DecoratedBox(
                        decoration: const BoxDecoration(
                          gradient: LinearGradient(
                            begin: Alignment.bottomCenter,
                            end: Alignment.topCenter,
                            colors: [Color(0xCC000000), Color(0x00000000)],
                          ),
                        ),
                        child: SafeArea(
                          top: false,
                          child: Padding(
                            padding: const EdgeInsets.fromLTRB(16, 24, 16, 8),
                            child: Column(mainAxisSize: MainAxisSize.min, children: [
                              if (pc.isLocal)
                                LocalSeekBar(playback: pc, onDark: true)
                              else
                              // Live stream: a full, non-seekable bar.
                              Row(children: [
                                const Icon(Icons.circle, color: Colors.redAccent, size: 10),
                                const SizedBox(width: 6),
                                const Text('LIVE', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 12)),
                                const SizedBox(width: 12),
                                const Expanded(
                                  child: ClipRRect(
                                    borderRadius: BorderRadius.all(Radius.circular(2)),
                                    child: LinearProgressIndicator(
                                      value: 1,
                                      minHeight: 4,
                                      color: Colors.redAccent,
                                      backgroundColor: Colors.white24,
                                    ),
                                  ),
                                ),
                              ]),
                              Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                                IconButton(
                                  tooltip: pc.isLocal ? 'Previous video' : 'Previous channel',
                                  color: Colors.white,
                                  iconSize: 36,
                                  icon: const Icon(Icons.skip_previous),
                                  onPressed: () => _act(pc.previous),
                                ),
                                const SizedBox(width: 24),
                                IconButton(
                                  tooltip: pc.isPlaying ? 'Pause' : 'Play',
                                  color: Colors.white,
                                  iconSize: 48,
                                  icon: Icon(pc.isPlaying ? Icons.pause_circle_filled : Icons.play_circle_fill),
                                  onPressed: () => _act(pc.togglePlay),
                                ),
                                const SizedBox(width: 24),
                                IconButton(
                                  tooltip: pc.isLocal ? 'Next video' : 'Next channel',
                                  color: Colors.white,
                                  iconSize: 36,
                                  icon: const Icon(Icons.skip_next),
                                  onPressed: () => _act(pc.next),
                                ),
                              ]),
                            ]),
                          ),
                        ),
                      ),
                    ),
                  ]),
                ),
              ),
            ]),
          ),
        );
      },
    );
  }
}

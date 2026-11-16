import 'package:material_ui/material_ui.dart';

import '../iptv_repository.dart';
import '../widgets/channel_tile.dart';
import 'player_screen.dart';

/// Spotify-style compact player pinned to the bottom of the screen: channel artwork and
/// previous / play-pause / next. Tapping it opens the full-screen player. Hidden when nothing
/// is loaded. It only drives the shared playback engine; it never plays anything itself.
class MiniPlayer extends StatelessWidget {
  const MiniPlayer({super.key, required this.repo});
  final IPTVRepository repo;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([repo.playback, repo.parked]),
      builder: (context, _) {
        final pc = repo.playback;
        final ch = pc.current;
        if (ch == null || !pc.hasMedia) return const SizedBox.shrink();

        final scheme = Theme.of(context).colorScheme;
        final big = repo.parked.info.automotive; // car displays: 64 dp touch targets
        final blocked = repo.parked.info.playbackBlocked;
        final status = pc.hasError
            ? 'Playback error'
            : pc.isBuffering
                ? 'Buffering…'
                : pc.isPlaying
                    ? 'Live'
                    : 'Paused';

        return Material(
          color: scheme.surfaceContainerHigh,
          elevation: 8,
          child: SafeArea(
            top: false,
            child: InkWell(
              onTap: () => PlayerScreen.open(context, repo),
              child: SizedBox(
                height: big ? 88 : 68,
                child: Row(children: [
                  const SizedBox(width: 12),
                  Hero(tag: PlayerScreen.artworkHero, child: ChannelLogo(url: ch.logo, size: big ? 64 : 48)),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(ch.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: Theme.of(context).textTheme.titleSmall),
                      Text(status, maxLines: 1, style: Theme.of(context).textTheme.bodySmall),
                    ]),
                  ),
                  IconButton(
                    iconSize: big ? 40 : 28,
                    style: big ? IconButton.styleFrom(minimumSize: const Size(64, 64)) : null,
                    tooltip: 'Previous channel',
                    icon: const Icon(Icons.skip_previous),
                    onPressed: pc.previous,
                  ),
                  IconButton.filled(
                    iconSize: big ? 40 : 28,
                    style: big ? IconButton.styleFrom(minimumSize: const Size(64, 64)) : null,
                    tooltip: pc.isPlaying ? 'Pause' : 'Play',
                    icon: Icon(pc.isPlaying ? Icons.pause : Icons.play_arrow),
                    onPressed: blocked ? null : pc.togglePlay,
                  ),
                  IconButton(
                    iconSize: big ? 40 : 28,
                    style: big ? IconButton.styleFrom(minimumSize: const Size(64, 64)) : null,
                    tooltip: 'Next channel',
                    icon: const Icon(Icons.skip_next),
                    onPressed: pc.next,
                  ),
                  const SizedBox(width: 4),
                ]),
              ),
            ),
          ),
        );
      },
    );
  }
}

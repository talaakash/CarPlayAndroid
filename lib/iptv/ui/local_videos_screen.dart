import 'package:material_ui/material_ui.dart';

import '../core/local_video.dart';
import '../iptv_repository.dart';
import 'mini_player.dart';
import 'player_screen.dart';

/// Library of videos picked from device storage. Tapping one plays it on the SAME native engine
/// IPTV uses (queue = the whole library, so Next / Previous step through the files), and the
/// player screen offers the same Mobile / Android Auto output selector.
class LocalVideosScreen extends StatelessWidget {
  const LocalVideosScreen({super.key, required this.repo, this.embedded = false});
  final IPTVRepository repo;

  /// true = just the list, for the Local Video tab (the home shell provides app bar and mini-player).
  final bool embedded;

  static Future<void> open(BuildContext context, IPTVRepository repo) =>
      Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => LocalVideosScreen(repo: repo)));

  /// System file picker; also used by the Local Video tab's app bar.
  static Future<void> addVideos(BuildContext context, IPTVRepository repo) => LocalVideosScreen(repo: repo)._add(context);

  Future<void> _add(BuildContext context) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      final n = await repo.local.pickAndAdd();
      if (n > 0) messenger.showSnackBar(SnackBar(content: Text(n == 1 ? 'Added 1 video' : 'Added $n videos')));
    } catch (e) {
      messenger.showSnackBar(const SnackBar(content: Text('Could not open the file picker')));
    }
  }

  Future<void> _play(BuildContext context, LocalVideo v) async {
    final queue = [for (final x in repo.local.videos) x.toChannel()];
    await repo.playback.play(v.toChannel(), queue);
    if (context.mounted) PlayerScreen.open(context, repo);
  }

  @override
  Widget build(BuildContext context) {
    if (embedded) return _body();
    return Scaffold(
      appBar: AppBar(
        title: const Text('Local videos'),
        actions: [IconButton(tooltip: 'Add videos', icon: const Icon(Icons.add), onPressed: () => _add(context))],
      ),
      bottomNavigationBar: MiniPlayer(repo: repo),
      body: _body(),
    );
  }

  Widget _body() {
    return Builder(builder: (context) {
      return ListenableBuilder(
        listenable: Listenable.merge([repo.local, repo.playback]),
        builder: (context, _) {
          final videos = repo.local.videos;
          if (!repo.local.loaded) return const Center(child: CircularProgressIndicator());
          if (videos.isEmpty) return _empty(context);
          final currentId = repo.playback.state.channelId;
          return ListView.builder(
            itemCount: videos.length,
            itemBuilder: (_, i) => _tile(context, videos[i], videos[i].id == currentId),
          );
        },
      );
    });
  }

  Widget _empty(BuildContext context) {
    final t = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Icon(Icons.video_library_outlined, size: 56, color: t.colorScheme.primary),
          const SizedBox(height: 12),
          Text('No local videos yet', style: t.textTheme.titleMedium),
          const SizedBox(height: 4),
          Text('Pick MP4, MKV, MOV and other video files from your device. They play on this phone or on the Android Auto display.',
              textAlign: TextAlign.center, style: t.textTheme.bodySmall),
          const SizedBox(height: 16),
          FilledButton.icon(onPressed: () => _add(context), icon: const Icon(Icons.add), label: const Text('Add videos')),
        ]),
      ),
    );
  }

  Widget _tile(BuildContext context, LocalVideo v, bool current) {
    final scheme = Theme.of(context).colorScheme;
    final playing = current && repo.playback.isPlaying;
    final meta = [LocalVideo.formatDuration(v.durationMs), LocalVideo.formatSize(v.sizeBytes)].where((e) => e.isNotEmpty).join(' · ');
    return ListTile(
      selected: current,
      onTap: () => _play(context, v),
      leading: SizedBox(
        width: 88,
        height: 50,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(6),
          child: Stack(fit: StackFit.expand, children: [
            ColoredBox(color: scheme.surfaceContainerHighest, child: const Icon(Icons.movie_outlined)),
            FutureBuilder(
              future: repo.local.thumbnail(v),
              builder: (_, snap) =>
                  snap.data == null ? const SizedBox.shrink() : Image.memory(snap.data!, fit: BoxFit.cover, gaplessPlayback: true),
            ),
            if (current)
              ColoredBox(
                color: Colors.black45,
                child: Icon(playing ? Icons.graphic_eq : Icons.pause, color: Colors.white),
              ),
          ]),
        ),
      ),
      title: Text(v.title, maxLines: 2, overflow: TextOverflow.ellipsis),
      subtitle: meta.isEmpty ? null : Text(meta),
      trailing: IconButton(
        tooltip: 'Remove from library',
        icon: const Icon(Icons.delete_outline),
        onPressed: () async {
          if (current) await repo.playback.stop();
          await repo.local.remove(v);
        },
      ),
    );
  }
}

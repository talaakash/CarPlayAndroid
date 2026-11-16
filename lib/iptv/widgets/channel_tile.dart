import 'package:cached_network_image/cached_network_image.dart';
import 'package:material_ui/material_ui.dart';

import '../core/channel.dart';

class ChannelTile extends StatelessWidget {
  const ChannelTile({
    super.key,
    required this.channel,
    required this.isFavorite,
    required this.isCurrent,
    this.isPlaying = false,
    required this.onTap,
    required this.onToggleFavorite,
  });

  final Channel channel;
  final bool isFavorite;
  final bool isCurrent;
  final bool isPlaying;
  final VoidCallback onTap;
  final VoidCallback onToggleFavorite;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ListTile(
      onTap: onTap,
      selected: isCurrent,
      leading: Stack(clipBehavior: Clip.none, children: [
        ChannelLogo(url: channel.logo, size: 48),
        if (isCurrent)
          Positioned(
            right: -4,
            bottom: -4,
            child: CircleAvatar(
              radius: 9,
              backgroundColor: scheme.primary,
              child: Icon(isPlaying ? Icons.graphic_eq : Icons.pause, size: 12, color: scheme.onPrimary),
            ),
          ),
      ]),
      title: Text(channel.name, maxLines: 1, overflow: TextOverflow.ellipsis),
      subtitle: Text('${channel.group} · ${channel.country}', maxLines: 1, overflow: TextOverflow.ellipsis),
      trailing: IconButton(
        tooltip: isFavorite ? 'Remove from favorites' : 'Add to favorites',
        icon: Icon(isFavorite ? Icons.favorite : Icons.favorite_border,
            color: isFavorite ? scheme.error : null),
        onPressed: onToggleFavorite,
      ),
    );
  }
}

class ChannelLogo extends StatelessWidget {
  const ChannelLogo({super.key, required this.url, this.size = 48});
  final String url;
  final double size;

  @override
  Widget build(BuildContext context) {
    final fallback = Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Icon(Icons.live_tv, size: size * 0.5),
    );
    if (!url.startsWith('http')) return fallback;
    return ClipRRect(
      borderRadius: BorderRadius.circular(6),
      child: CachedNetworkImage(
        imageUrl: url,
        width: size,
        height: size,
        fit: BoxFit.contain,
        placeholder: (_, _) => fallback,
        errorWidget: (_, _, _) => fallback,
      ),
    );
  }
}

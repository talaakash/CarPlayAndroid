import 'channel.dart';

/// Pure M3U / M3U8 playlist parser (no I/O, safe to run in an isolate).
class M3UParser {
  const M3UParser._();

  static final RegExp _attr = RegExp(r'([A-Za-z0-9_-]+)="([^"]*)"');

  static List<Channel> parse(String content) {
    final channels = <Channel>[];
    final seen = <String>{};

    String? extinf;
    String? extGroup;

    for (final raw in content.split(RegExp(r'\r?\n'))) {
      final line = raw.trim();
      if (line.isEmpty) continue;

      if (line.startsWith('#EXTINF')) {
        extinf = line;
        extGroup = null;
      } else if (line.startsWith('#EXTGRP:')) {
        extGroup = line.substring(8).trim();
      } else if (line.startsWith('#')) {
        continue; // #EXTM3U, #EXTVLCOPT, comments ...
      } else if (extinf != null && _looksLikeUrl(line)) {
        final channel = _build(extinf, line, extGroup);
        if (seen.add(channel.id)) channels.add(channel);
        extinf = null;
        extGroup = null;
      }
    }
    return channels;
  }

  static bool _looksLikeUrl(String s) => s.contains('://');

  static Channel _build(String extinf, String url, String? extGroup) {
    final attrs = <String, String>{
      for (final m in _attr.allMatches(extinf)) m.group(1)!.toLowerCase(): m.group(2)!,
    };

    final name = _title(extinf).isNotEmpty
        ? _title(extinf)
        : (attrs['tvg-name'] ?? 'Unnamed channel');
    final tvgId = attrs['tvg-id'] ?? '';
    final logo = attrs['tvg-logo'] ?? '';

    var group = (attrs['group-title'] ?? extGroup ?? '').replaceAll(';', ', ').trim();
    if (group.isEmpty || group.toLowerCase() == 'undefined') group = 'Others';

    var country = 'Unknown';
    if (tvgId.contains('.')) {
      country = tvgId.split('.').last.split('@').first.toUpperCase();
    } else if ((attrs['tvg-country'] ?? '').isNotEmpty) {
      country = attrs['tvg-country']!.toUpperCase();
    }
    if (country.isEmpty) country = 'Unknown';

    return Channel.create(
      name: name,
      url: url,
      logo: logo,
      group: group,
      country: country,
      tvgId: tvgId,
    );
  }

  /// Text after the first comma that is outside of a quoted attribute value.
  static String _title(String extinf) {
    var inQuotes = false;
    for (var i = 0; i < extinf.length; i++) {
      final c = extinf[i];
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (c == ',' && !inQuotes) {
        return extinf.substring(i + 1).trim();
      }
    }
    return '';
  }
}

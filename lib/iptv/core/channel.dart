/// A single IPTV channel. [id] is a stable hash of url+group+name so that the same
/// id is used by favorites, recents, Flutter UI, Android Auto and CarPlay.
class Channel {
  const Channel({
    required this.id,
    required this.name,
    required this.url,
    this.logo = '',
    this.group = 'Others',
    this.country = 'Unknown',
    this.tvgId = '',
  });

  final String id;
  final String name;
  final String url;
  final String logo;
  final String group;
  final String country;
  final String tvgId;

  factory Channel.create({
    required String name,
    required String url,
    String logo = '',
    String group = 'Others',
    String country = 'Unknown',
    String tvgId = '',
  }) =>
      Channel(
        id: stableId('$url|$group|$name'),
        name: name,
        url: url,
        logo: logo,
        group: group,
        country: country,
        tvgId: tvgId,
      );

  /// Two interleaved 32-bit FNV-1a hashes -> 16 hex chars (works on web too).
  static String stableId(String input) {
    var h1 = 0x811c9dc5;
    var h2 = 0x01000193;
    for (final unit in input.codeUnits) {
      h1 = ((h1 ^ unit) * 0x01000193) & 0xffffffff;
      h2 = ((h2 + unit) * 0x85ebca6b) & 0xffffffff;
      h2 ^= h2 >> 13;
    }
    return h1.toRadixString(16).padLeft(8, '0') + h2.toRadixString(16).padLeft(8, '0');
  }

  Map<String, Object?> toJson() => {
        'id': id,
        'name': name,
        'url': url,
        'logo': logo,
        'group': group,
        'country': country,
        'tvgId': tvgId,
      };

  factory Channel.fromJson(Map<String, dynamic> j) => Channel(
        id: j['id'] as String,
        name: j['name'] as String,
        url: j['url'] as String,
        logo: (j['logo'] as String?) ?? '',
        group: (j['group'] as String?) ?? 'Others',
        country: (j['country'] as String?) ?? 'Unknown',
        tvgId: (j['tvgId'] as String?) ?? '',
      );
}

import '../core/channel.dart';

/// In-memory channel catalog with search and filtering.
class ChannelService {
  List<Channel> _channels = const [];
  Map<String, Channel> _byId = const {};

  List<Channel> get all => _channels;

  void setChannels(List<Channel> channels) {
    _channels = channels;
    _byId = {for (final c in channels) c.id: c};
  }

  Channel? byId(String id) => _byId[id];

  List<Channel> byIds(Iterable<String> ids) =>
      [for (final id in ids) if (_byId[id] != null) _byId[id]!];

  /// Case-insensitive name search combined with group / country filters.
  /// Empty filter sets mean "no filter".
  List<Channel> query({
    String search = '',
    Set<String> groups = const {},
    Set<String> countries = const {},
  }) {
    final q = search.trim().toLowerCase();
    if (q.isEmpty && groups.isEmpty && countries.isEmpty) return _channels;
    return _channels.where((c) {
      if (groups.isNotEmpty && !groups.contains(c.group)) return false;
      if (countries.isNotEmpty && !countries.contains(c.country)) return false;
      return q.isEmpty || c.name.toLowerCase().contains(q);
    }).toList();
  }
}

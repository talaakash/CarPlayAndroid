import '../core/channel.dart';

/// Groups (categories) and countries derived from the channel list.
class CategoryService {
  List<String> _groups = const [];
  List<String> _countries = const [];
  Map<String, int> _counts = const {};

  List<String> get groups => _groups;
  List<String> get countries => _countries;
  int countFor(String group) => _counts[group] ?? 0;

  void rebuild(List<Channel> channels) {
    final counts = <String, int>{};
    final countries = <String>{};
    for (final c in channels) {
      counts[c.group] = (counts[c.group] ?? 0) + 1;
      countries.add(c.country);
    }
    _counts = counts;
    _groups = counts.keys.toList()..sort((a, b) => a.toLowerCase().compareTo(b.toLowerCase()));
    _countries = countries.toList()..sort();
  }
}

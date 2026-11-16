import 'package:flutter/foundation.dart';
import 'package:iptv_native/iptv_native.dart';

import '../core/shared_files.dart';

/// Recently watched channel ids. The native playback engine records every channel it
/// starts (whether from Flutter, Android Auto or CarPlay) into recents.json; this
/// service only reads it and asks native to clear it.
class RecentlyWatchedService extends ChangeNotifier {
  RecentlyWatchedService(this._files);
  final SharedFiles _files;

  List<String> _ids = [];
  List<String> get ids => List.unmodifiable(_ids);

  Future<void> reload() async {
    final j = await _files.readJson('recents.json');
    final next = (j is Map && j['recents'] is List)
        ? (j['recents'] as List).whereType<String>().toList()
        : <String>[];
    if (!listEquals(next, _ids)) {
      _ids = next;
      notifyListeners();
    }
  }

  Future<void> clear() async {
    await IptvNative.instance.clearRecents();
    await reload();
  }
}

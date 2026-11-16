import 'package:flutter/foundation.dart';
import 'package:iptv_native/iptv_native.dart';

import '../core/shared_files.dart';

/// Favorite channel ids, persisted in favorites.json (shared with the car UIs).
class FavoriteService extends ChangeNotifier {
  FavoriteService(this._files);
  final SharedFiles _files;
  static const _name = 'favorites.json';

  List<String> _ids = [];
  List<String> get ids => List.unmodifiable(_ids);
  bool isFavorite(String id) => _ids.contains(id);

  Future<void> load() async {
    final j = await _files.readJson(_name);
    if (j is Map && j['favorites'] is List) {
      _ids = (j['favorites'] as List).whereType<String>().toList();
    }
    notifyListeners();
  }

  Future<void> toggle(String id) async {
    await load(); // the car may have changed the file since we last read it
    if (_ids.contains(id)) {
      _ids = [..._ids]..remove(id);
    } else {
      _ids = [id, ..._ids];
    }
    notifyListeners();
    await _files.writeString(_name, '{"favorites":${_encode(_ids)}}');
    await IptvNative.instance.notifyDataChanged(); // refresh Android Auto / CarPlay lists
  }

  static String _encode(List<String> ids) => '[${ids.map((e) => '"$e"').join(',')}]';
}

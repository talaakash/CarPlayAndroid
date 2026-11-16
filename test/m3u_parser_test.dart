import 'package:car_play/iptv/core/m3u_parser.dart';
import 'package:car_play/iptv/services/channel_service.dart';
import 'package:car_play/iptv/services/category_service.dart';
import 'package:flutter_test/flutter_test.dart';

const _playlist = '''#EXTM3U
#EXTINF:-1 tvg-id="BBCNews.uk@SD" tvg-logo="https://x/y.png" group-title="News;General",BBC News, HD
http://example.com/bbc.m3u8
#EXTINF:-1 tvg-id="" group-title="",No Group
https://example.com/nogroup.m3u8
#EXTINF:-1 group-title="Sports",Sport One
#EXTVLCOPT:http-user-agent=x
https://example.com/sport.m3u8
#EXTINF:-1,Broken (no url follows)
#EXTINF:-1 group-title="Sports",Sport One
https://example.com/sport.m3u8
''';

void main() {
  test('parses attributes, names with commas, groups, countries and dedupes', () {
    final c = M3UParser.parse(_playlist);
    expect(c.length, 3); // duplicate + broken entry removed
    expect(c[0].name, 'BBC News, HD');
    expect(c[0].group, 'News, General');
    expect(c[0].country, 'UK');
    expect(c[0].logo, 'https://x/y.png');
    expect(c[1].group, 'Others');
    expect(c[1].country, 'Unknown');
    expect(c[2].name, 'Sport One');
    expect(c[2].url, 'https://example.com/sport.m3u8');
  });

  test('ids are stable and unique', () {
    final a = M3UParser.parse(_playlist);
    final b = M3UParser.parse(_playlist);
    expect(a.map((e) => e.id), b.map((e) => e.id));
    expect(a.map((e) => e.id).toSet().length, a.length);
  });

  test('search, filter and categories', () {
    final all = M3UParser.parse(_playlist);
    final svc = ChannelService()..setChannels(all);
    expect(svc.query(search: 'sport').single.name, 'Sport One');
    expect(svc.query(countries: {'UK'}).length, 1);
    expect(svc.query(groups: {'Others'}, search: 'nothing'), isEmpty);
    final cats = CategoryService()..rebuild(all);
    expect(cats.groups, ['News, General', 'Others', 'Sports']);
    expect(cats.countFor('Sports'), 1);
  });
}

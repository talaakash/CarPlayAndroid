import 'package:car_play/iptv/core/channel.dart';
import 'package:car_play/iptv/core/local_video.dart';
import 'package:car_play/iptv/services/channel_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('local video maps to a channel with its content URI and a stable id', () {
    final v = LocalVideo.create(uri: 'content://docs/video%3A1', name: 'Holiday.mkv', durationMs: 65000);
    final c = v.toChannel();
    expect(c.url, 'content://docs/video%3A1');
    expect(c.name, 'Holiday');
    expect(c.id, v.id);
    expect(LocalVideo.create(uri: v.uri, name: 'x').id, v.id);
  });

  test('formatting', () {
    expect(LocalVideo.formatDuration(65000), '1:05');
    expect(LocalVideo.formatDuration(3723000), '1:02:03');
    expect(LocalVideo.formatDuration(-1), '');
    expect(LocalVideo.formatSize(5 * 1024 * 1024), '5 MB');
  });

  test('ChannelService resolves local videos without adding them to the IPTV list', () {
    final s = ChannelService()
      ..setChannels([Channel.create(name: 'A', url: 'http://a')])
      ..setLocal([LocalVideo.create(uri: 'content://x', name: 'v.mp4').toChannel()]);
    final id = LocalVideo.create(uri: 'content://x', name: 'v.mp4').id;
    expect(s.byId(id)?.name, 'v');
    expect(s.isLocal(id), isTrue);
    expect(s.all.length, 1);
  });
}

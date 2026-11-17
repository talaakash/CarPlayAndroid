import 'package:iptv_native/iptv_native.dart';

import '../core/channel.dart';

/// Thin Dart wrapper over the native playback engine (ExoPlayer/Media3 on Android,
/// AVPlayer on iOS). There is only ONE engine per platform; Flutter, Android Auto and
/// CarPlay all drive it, so they can never fight over a stream.
class IPTVPlayerService {
  final IptvNative _native = IptvNative.instance;

  Stream<NativePlaybackState> get states => _native.playbackState;
  Stream<void> get userDataChanges => _native.userDataChanged;

  Future<NativePlaybackState> currentState() => _native.currentState();

  NativeChannel _n(Channel c) =>
      NativeChannel(id: c.id, name: c.name, url: c.url, logo: c.logo, group: c.group);

  Future<void> play(Channel channel, List<Channel> queue) =>
      _native.play(_n(channel), queue.map(_n).toList());

  Future<void> resume() => _native.resume();
  Future<void> pause() => _native.pause();
  Future<void> stop() => _native.stop();
  Future<void> next() => _native.next();
  Future<void> previous() => _native.previous();
  Future<void> retry() => _native.retry();
  Future<void> seekTo(int ms) => _native.seekTo(ms);
  Future<({int positionMs, int durationMs})> position() => _native.position();

  Future<bool> pipSupported() => _native.isPictureInPictureSupported();
  Future<void> enterPip() => _native.enterPictureInPicture();
  Future<void> setAutoPip(bool v) => _native.setAutoPictureInPicture(v);
}

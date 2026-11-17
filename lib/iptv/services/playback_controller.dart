import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:iptv_native/iptv_native.dart';

import '../core/channel.dart';
import 'channel_service.dart';
import 'player_service.dart';

/// UI-facing playback state. Mirrors the native engine's state, so a channel started
/// from Android Auto / CarPlay shows up here immediately (and vice versa).
class PlaybackController extends ChangeNotifier {
  PlaybackController(this._player, this._channels) {
    _sub = _player.states.listen(_onState);
    _player.currentState().then(_onState).catchError((_) {});
  }

  final IPTVPlayerService _player;
  final ChannelService _channels;
  StreamSubscription<NativePlaybackState>? _sub;

  NativePlaybackState _state = const NativePlaybackState();
  NativePlaybackState get state => _state;

  Channel? get current =>
      _state.channelId == null ? null : _channels.byId(_state.channelId!);
  /// True when the current item is a local video (seekable, not live).
  bool get isLocal => _state.isLocal;
  bool get isPlaying => _state.isPlaying;
  bool get isBuffering => _state.status == NativePlaybackStatus.buffering;
  bool get hasError => _state.status == NativePlaybackStatus.error;
  bool get hasMedia => _state.channelId != null && _state.status != NativePlaybackStatus.idle;

  void _onState(NativePlaybackState s) {
    _state = s;
    notifyListeners();
  }

  Future<void> play(Channel channel, List<Channel> queue) => _player.play(channel, queue);
  Future<void> togglePlay() => isPlaying ? _player.pause() : _player.resume();
  Future<void> pause() => _player.pause();
  Future<void> stop() => _player.stop();
  Future<void> next() => _player.next();
  Future<void> previous() => _player.previous();
  Future<void> retry() => _player.retry();
  Future<({int positionMs, int durationMs})> position() => _player.position();
  Future<void> seekTo(Duration d) => _player.seekTo(d.inMilliseconds);
  Future<void> seekBy(Duration d) async {
    final p = await _player.position();
    final target = p.positionMs + d.inMilliseconds;
    await _player.seekTo(p.durationMs > 0 ? target.clamp(0, p.durationMs) : target.clamp(0, 1 << 40));
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }
}

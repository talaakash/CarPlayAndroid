import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../core/local_video.dart';
import '../services/playback_controller.dart';

/// Position / duration slider for a local video. Polls the shared native player while mounted
/// (the engine is the single source of truth, so seeks from Android Auto show up here too).
class LocalSeekBar extends StatefulWidget {
  const LocalSeekBar({super.key, required this.playback, this.onDark = false});
  final PlaybackController playback;

  /// White text / track for use over video.
  final bool onDark;

  @override
  State<LocalSeekBar> createState() => _LocalSeekBarState();
}

class _LocalSeekBarState extends State<LocalSeekBar> {
  Timer? _timer;
  int _pos = 0;
  int _dur = -1;
  double? _drag; // fraction while the thumb is held

  @override
  void initState() {
    super.initState();
    _pos = widget.playback.state.positionMs;
    _dur = widget.playback.state.durationMs;
    _poll();
    _timer = Timer.periodic(const Duration(milliseconds: 500), (_) => _poll());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _poll() async {
    if (_drag != null) return;
    try {
      final p = await widget.playback.position();
      if (mounted) setState(() { _pos = p.positionMs; _dur = p.durationMs; });
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final color = widget.onDark ? Colors.white : Theme.of(context).colorScheme.onSurface;
    final seekable = _dur > 0;
    final frac = seekable ? (_drag ?? (_pos / _dur)).clamp(0.0, 1.0) : 0.0;
    final shown = seekable && _drag != null ? (_dur * _drag!).round() : _pos;
    final style = TextStyle(color: color, fontSize: 12, fontFeatures: const [FontFeature.tabularFigures()]);
    return Row(children: [
      SizedBox(width: 48, child: Text(LocalVideo.formatDuration(shown).ifEmpty('0:00'), style: style)),
      Expanded(
        child: SliderTheme(
          data: SliderTheme.of(context).copyWith(
            trackHeight: 3,
            thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
            overlayShape: const RoundSliderOverlayShape(overlayRadius: 14),
            activeTrackColor: widget.onDark ? Colors.white : null,
            inactiveTrackColor: widget.onDark ? Colors.white24 : null,
            thumbColor: widget.onDark ? Colors.white : null,
          ),
          child: Slider(
            value: frac,
            onChanged: seekable ? (v) => setState(() => _drag = v) : null,
            onChangeEnd: (v) async {
              final target = (_dur * v).round();
              setState(() { _drag = null; _pos = target; });
              await widget.playback.seekTo(Duration(milliseconds: target));
            },
          ),
        ),
      ),
      SizedBox(width: 48, child: Text(seekable ? LocalVideo.formatDuration(_dur) : '--:--', textAlign: TextAlign.end, style: style)),
    ]);
  }
}

extension on String {
  String ifEmpty(String other) => isEmpty ? other : this;
}

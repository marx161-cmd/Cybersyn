# State Machine

Core state:

- `clutch_active`: movement processing enabled.
- `mouse_held`: left button currently held.
- `last_yaw`, `last_pitch`: previous orientation values.
- `remainder_x`, `remainder_y`: smoothing carry-over.
- `last_event_ts`: last control/sensor event timestamp.

Transitions:

1. `button.clutch_toggle`
- Flip `clutch_active`.
- Reset `last_yaw`/`last_pitch`.
- If clutch becomes inactive and `mouse_held` is true, release left button.

2. `button.click_toggle`
- Toggle `mouse_held`.
- Emit left down/up accordingly.

3. `sensor.rotation_vector`
- Ignored when clutch is inactive.
- Parse quaternion, compute yaw/pitch deltas.
- Apply unwrap, deadzone, curve, sensitivity, smoothing.
- Emit relative mouse movement.

4. Tick loop
- If clutch active, mouse held, and idle timeout exceeded:
  release left button.

5. Shutdown
- Always release left button before exit.

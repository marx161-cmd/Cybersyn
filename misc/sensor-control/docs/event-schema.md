# Event Schema

Canonical event format used by `sensor-control`:

```json
{
  "event": "sensor.rotation_vector | button.clutch_toggle | button.click_toggle",
  "timestamp": 1739700000.123,
  "data": {}
}
```

## `sensor.rotation_vector`

```json
{
  "event": "sensor.rotation_vector",
  "timestamp": 1739700000.123,
  "data": {
    "type": "android.sensor.game_rotation_vector",
    "values": [x, y, z, w]
  }
}
```

## `button.clutch_toggle`

```json
{
  "event": "button.clutch_toggle",
  "timestamp": 1739700000.123,
  "data": {}
}
```

## `button.click_toggle`

```json
{
  "event": "button.click_toggle",
  "timestamp": 1739700000.123,
  "data": {}
}
```

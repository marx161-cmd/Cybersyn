#!/data/data/com.termux/files/usr/bin/bash
# Toggle the gyro-cursor gate (android/clutch) on comrade. Publishes through Cybersyn's
# one persistent MQTT connection via cybersyn-send (was: its own mosquitto_pub, now removed).
/data/data/com.termux/files/usr/bin/cybersyn-send "android/clutch" "TOGGLE"

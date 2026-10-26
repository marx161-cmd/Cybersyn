package com.termux.cybersyn.stub2;

/** GENERATED with res/layout/island_remote.xml (Cybersyn scope.md 2026-10-26 ISLAND REMOTE): pad ids + view ids. */
final class IslandTable {
    private IslandTable() { }

    /** MODE picker: {pad button id, label}; CONFIRM presses the picked id. View ids isl_m0.. in this order. */
    static final String[][] MODES = {
            {"NORMAL", "NORMAL"},
            {"SLINGSHOT", "SLING"},
            {"ROSETTE", "ROSETTE"},
            {"ECCENTRIC", "ECCENTRIC"},
            {"SHEPHERD", "SHEPHERD"},
            {"RESONANCE", "RESONANCE"},
            {"WEAVE", "WEAVE"},
            {"PING-PONG", "PING-PONG"}
    };

    /** Plain buttons: {pad button id, view id name}. */
    static final String[][] BUTTONS = {
            {"STAR SLING", "isl_b0_0"},
            {"MOON", "isl_b0_1"},
            {"VIEW", "isl_b0_2"},
            {"RIDE", "isl_b0_3"},
            {"STAR WHITE", "isl_b1_0"},
            {"STAR ORANGE", "isl_b1_1"},
            {"STAR RED", "isl_b1_2"},
            {"SKIN PHONE", "isl_b2_0"},
            {"SKIN LASER", "isl_b2_1"},
            {"SKIN RADAR", "isl_b2_2"},
            {"MOUSE OFF", "isl_b3_0"},
            {"MOUSE BOOP", "isl_b3_1"},
            {"MOUSE GRAB", "isl_b3_2"}
    };

    /** Latched switches: {pad switch id, label, view id name}. */
    static final String[][] SWITCHES = {
            {"farming off", "FARMING", "isl_s0"},
            {"off", "REMOTE", "isl_s1"}
    };
}

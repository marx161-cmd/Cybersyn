package com.termux.cybersyn.core

import com.termux.cybersyn.core.actions.AirplaneModeAction
import com.termux.cybersyn.core.actions.AppendFileAction
import com.termux.cybersyn.core.actions.BluetoothToggleAction
import com.termux.cybersyn.core.actions.BrightnessAction
import com.termux.cybersyn.core.actions.DataReadAction
import com.termux.cybersyn.core.actions.DateTimeAddAction
import com.termux.cybersyn.core.actions.DateTimeFormatAction
import com.termux.cybersyn.core.actions.DateTimeParseAction
import com.termux.cybersyn.core.actions.DoNotDisturbAction
import com.termux.cybersyn.core.actions.DeleteFileAction
import com.termux.cybersyn.core.actions.DownloadAction
import com.termux.cybersyn.core.actions.GoHomeAction
import com.termux.cybersyn.core.actions.HttpGetAction
import com.termux.cybersyn.core.actions.HttpPostAction
import com.termux.cybersyn.core.actions.HttpRequestAction
import com.termux.cybersyn.core.actions.KillAppAction
import com.termux.cybersyn.core.actions.LaunchAppAction
import com.termux.cybersyn.core.actions.LaunchIntentAction
import com.termux.cybersyn.core.actions.LocalePluginConditionQueryAction
import com.termux.cybersyn.core.actions.LocalePluginSettingAction
import com.termux.cybersyn.core.actions.ListFilesAction
import com.termux.cybersyn.core.actions.LockDeviceAction
import com.termux.cybersyn.core.actions.LogAction
import com.termux.cybersyn.core.actions.MobileDataAction
import com.termux.cybersyn.core.actions.MqttPublishAction
import com.termux.cybersyn.core.actions.MuteAction
import com.termux.cybersyn.core.actions.MediaPlayAction
import com.termux.cybersyn.core.actions.MediaPauseAction
import com.termux.cybersyn.core.actions.MediaPlayPauseAction
import com.termux.cybersyn.core.actions.MediaNextAction
import com.termux.cybersyn.core.actions.MediaPrevAction
import com.termux.cybersyn.core.actions.MediaStopAction
import com.termux.cybersyn.core.actions.MediaSeekAction
import com.termux.cybersyn.core.actions.MediaSetPositionAction
import com.termux.cybersyn.core.actions.MediaSetVolumeAction
import com.termux.cybersyn.core.actions.MediaSetLoopAction
import com.termux.cybersyn.core.actions.MediaSetShuffleAction
import com.termux.cybersyn.core.actions.MediaRequestAlbumArtAction
import com.termux.cybersyn.core.actions.ClipboardPushAction
import com.termux.cybersyn.core.actions.ClipboardPullAction
import com.termux.cybersyn.core.actions.FileServeAction
import com.termux.cybersyn.core.actions.FileReceiveAction
import com.termux.cybersyn.core.actions.MpvEnterFreeformAction
import com.termux.cybersyn.core.actions.MpvEnterPipAction
import com.termux.cybersyn.core.actions.MpvExitFullscreenAction
import com.termux.cybersyn.core.actions.MpvTogglePlayAction
import com.termux.cybersyn.core.actions.MpvTogglePauseAction
import com.termux.cybersyn.core.actions.MpvAspectWidescreenAction
import com.termux.cybersyn.core.actions.MpvAspectCinemaAction
import com.termux.cybersyn.core.actions.MpvAspectSquareAction
import com.termux.cybersyn.core.actions.NextTrackAction
import com.termux.cybersyn.core.actions.NotifyAction
import com.termux.cybersyn.core.actions.NotifyCancelAction
import com.termux.cybersyn.core.actions.OpenUrlAction
import com.termux.cybersyn.core.actions.PauseSoundAction
import com.termux.cybersyn.core.actions.PersistVariableAction
import com.termux.cybersyn.core.actions.PingAction
import com.termux.cybersyn.core.actions.PlaySoundAction
import com.termux.cybersyn.core.actions.PreviousTrackAction
import com.termux.cybersyn.core.actions.ReadFileAction
import com.termux.cybersyn.core.actions.RingerModeAction
import com.termux.cybersyn.core.actions.RebootAction
import com.termux.cybersyn.core.actions.SayAction
import com.termux.cybersyn.core.actions.ScreenOffAction
import com.termux.cybersyn.core.actions.ScreenTimeoutAction
import com.termux.cybersyn.core.actions.ScreenshotAction
import com.termux.cybersyn.core.actions.SendSmsAction
import com.termux.cybersyn.core.actions.SetVariableAction
import com.termux.cybersyn.core.actions.TextMatchAction
import com.termux.cybersyn.core.actions.TextReplaceAction
import com.termux.cybersyn.core.actions.TextSplitAction
import com.termux.cybersyn.core.actions.TextJoinAction
import com.termux.cybersyn.core.actions.TextSubstringAction
import com.termux.cybersyn.core.actions.StopSoundAction
import com.termux.cybersyn.core.actions.TaskerUnsupportedAction
import com.termux.cybersyn.core.actions.TermuxScriptAction
import com.termux.cybersyn.core.actions.TileStateAction
import com.termux.cybersyn.core.actions.TorchAction
import com.termux.cybersyn.core.actions.VibrateAction
import com.termux.cybersyn.core.actions.VolumeAction
import com.termux.cybersyn.core.actions.WaitAction
import com.termux.cybersyn.core.actions.WakeAction
import com.termux.cybersyn.core.actions.WakeOnLanAction
import com.termux.cybersyn.core.actions.WiFiToggleAction
import com.termux.cybersyn.core.actions.WriteFileAction
import com.termux.cybersyn.core.contexts.ApplicationContextSourceImpl
import com.termux.cybersyn.core.contexts.ClipboardContextSource
import com.termux.cybersyn.core.contexts.ContextSourceRegistry
import com.termux.cybersyn.core.contexts.EventContextSourceImpl
import com.termux.cybersyn.core.contexts.FileOfferContextSource
import com.termux.cybersyn.core.contexts.LocalePluginConditionContextSource
import com.termux.cybersyn.core.contexts.LocationContextSourceImpl
import com.termux.cybersyn.core.contexts.MediaContextSource
import com.termux.cybersyn.core.contexts.StateContextSourceImpl
import com.termux.cybersyn.core.contexts.TimeContextSourceImpl
import com.termux.cybersyn.core.engine.ActionRegistry

fun registerCoreRuntime() {
    registerBuiltInActions()
    registerContextSources()
}

private fun registerBuiltInActions() {
    listOf(
        NotifyAction(),
        NotifyCancelAction(),
        SetVariableAction(),
        PersistVariableAction(),
        DataReadAction(),
        DateTimeFormatAction(),
        DateTimeParseAction(),
        DateTimeAddAction(),
        TextMatchAction(),
        TextReplaceAction(),
        TextSplitAction(),
        TextJoinAction(),
        TextSubstringAction(),
        SayAction(),
        WaitAction(),
        LaunchIntentAction(),
        WiFiToggleAction(),
        BluetoothToggleAction(),
        BrightnessAction(),
        VolumeAction(),
        AirplaneModeAction(),
        MobileDataAction(),
        ScreenTimeoutAction(),
        DoNotDisturbAction(),
        RingerModeAction(),
        TorchAction(),
        TileStateAction(),
        LaunchAppAction(),
        LocalePluginSettingAction(),
        LocalePluginConditionQueryAction(),
        KillAppAction(),
        GoHomeAction(),
        OpenUrlAction(),
        SendSmsAction(),
        ScreenshotAction(),
        ReadFileAction(),
        WriteFileAction(),
        AppendFileAction(),
        DeleteFileAction(),
        ListFilesAction(),
        HttpRequestAction(),
        HttpGetAction(),
        HttpPostAction(),
        PingAction(),
        DownloadAction(),
        WakeOnLanAction(),
        MqttPublishAction(),
        MpvEnterFreeformAction(),
        MpvEnterPipAction(),
        MpvExitFullscreenAction(),
        MpvTogglePlayAction(),
        MpvTogglePauseAction(),
        MpvAspectWidescreenAction(),
        MpvAspectCinemaAction(),
        MpvAspectSquareAction(),
        PlaySoundAction(),
        StopSoundAction(),
        PauseSoundAction(),
        NextTrackAction(),
        PreviousTrackAction(),
        MuteAction(),
        VibrateAction(),
        RebootAction(),
        LockDeviceAction(),
        ScreenOffAction(),
        WakeAction(),
        LogAction(),
        TermuxScriptAction(),
        TaskerUnsupportedAction(),
        // Cybersyn KDE feature set
        MediaPlayAction(),
        MediaPauseAction(),
        MediaPlayPauseAction(),
        MediaNextAction(),
        MediaPrevAction(),
        MediaStopAction(),
        MediaSeekAction(),
        MediaSetPositionAction(),
        MediaSetVolumeAction(),
        MediaSetLoopAction(),
        MediaSetShuffleAction(),
        MediaRequestAlbumArtAction(),
        ClipboardPushAction(),
        ClipboardPullAction(),
        FileServeAction(),
        FileReceiveAction(),
    ).forEach(ActionRegistry::register)
}

private fun registerContextSources() {
    listOf(
        ApplicationContextSourceImpl(),
        TimeContextSourceImpl(),
        StateContextSourceImpl(),
        EventContextSourceImpl(),
        LocationContextSourceImpl(),
        LocalePluginConditionContextSource(),
        MediaContextSource(),
        ClipboardContextSource(),
        FileOfferContextSource(),
    ).forEach(ContextSourceRegistry::register)
}

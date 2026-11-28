package com.leyu.melora.playback

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputRouteTest {
    private class Output(var types: List<Int>)

    @Test
    fun bluetoothOutputDeviceClassificationMatchesMediaDevicesOnly() {
        assertTrue(isBluetoothAudioOutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(isBluetoothAudioOutputDevice(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertTrue(isBluetoothAudioOutputDevice(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertTrue(isBluetoothAudioOutputDevice(AudioDeviceInfo.TYPE_BLE_BROADCAST))
        assertTrue(isBluetoothAudioOutputDevice(AudioDeviceInfo.TYPE_HEARING_AID))
        assertFalse(isBluetoothAudioOutputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(isBluetoothAudioOutputDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertFalse(isBluetoothAudioOutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
    }

    @Test fun actualRouteDeterminesBluetoothEvenWithSeveralOutputs() {
        val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val output = Output(listOf(speaker))
        val route = AudioOutputRoute(Output::types)

        route.attach(output)
        route.refresh(output)
        assertFalse(route.bluetoothOutput.value)

        output.types = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        route.refresh(output)
        assertTrue(route.bluetoothOutput.value)

        output.types = listOf(speaker, AudioDeviceInfo.TYPE_BLE_HEARING_AID)
        route.refresh(output)
        assertTrue(route.bluetoothOutput.value)

        output.types = listOf(speaker, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        route.refresh(output)
        assertFalse(route.bluetoothOutput.value)
    }

    @Test fun inactiveRouteClearsBluetoothAndResumeReadsItAgain() {
        val bluetooth = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET)
        val output = Output(bluetooth)
        val route = AudioOutputRoute(Output::types)
        route.attach(output)
        route.refresh(output)
        assertTrue(route.bluetoothOutput.value)

        output.types = emptyList()
        route.refresh(output)
        assertFalse(route.bluetoothOutput.value)
        route.refresh(output)
        assertFalse(route.bluetoothOutput.value)

        output.types = bluetooth
        route.refresh(output)
        assertTrue(route.bluetoothOutput.value)
    }

    @Test fun callbacksFromReleasedAndReplacedTracksCannotChangeTheCurrentRoute() {
        val bluetooth = listOf(AudioDeviceInfo.TYPE_HEARING_AID)
        val first = Output(bluetooth)
        val second = Output(bluetooth)
        val route = AudioOutputRoute(Output::types)
        route.attach(first)
        route.refresh(first)
        assertTrue(route.bluetoothOutput.value)

        route.attach(second)
        assertFalse(route.bluetoothOutput.value)
        route.refresh(first)
        assertFalse(route.bluetoothOutput.value)
        route.refresh(second)
        route.detach(first)
        assertSame(second, route.track)
        assertTrue(route.bluetoothOutput.value)

        route.detach(second)
        route.refresh(second)
        assertNull(route.track)
        assertFalse(route.bluetoothOutput.value)
    }
}

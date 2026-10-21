package kr.co.nucode.ble

/**
 * Android API operations that may appear in public [PlatformDetail.Android.apiOperation]. Keeping
 * this closed at the mapper boundary prevents exception text or caller-supplied identifiers from
 * reaching the public failure value.
 */
internal enum class AndroidApiOperation(internal val wireName: String) {
    StartScan("startScan"),
    OnScanFailed("onScanFailed"),
    ConnectGatt("connectGatt"),
    DiscoverServices("discoverServices"),
    SetCharacteristicNotification("setCharacteristicNotification"),
    ReadCharacteristic("readCharacteristic"),
    WriteCharacteristic("writeCharacteristic"),
    ReadDescriptor("readDescriptor"),
    WriteDescriptor("writeDescriptor"),
    RequestMtu("requestMtu"),
    OnConnectionStateChange("onConnectionStateChange"),
    OnServicesDiscovered("onServicesDiscovered"),
    OnMtuChanged("onMtuChanged"),
    OnCharacteristicRead("onCharacteristicRead"),
    OnCharacteristicWrite("onCharacteristicWrite"),
    OnDescriptorRead("onDescriptorRead"),
    OnDescriptorWrite("onDescriptorWrite"),
}

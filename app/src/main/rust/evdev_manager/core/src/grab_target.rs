use evdev::enums::EventCode;

use crate::evdev_device_info::EvdevDeviceInfo;

/// A bus/vendor/product value of this sentinel matches any value. Used for the RAPOO
/// keyboard, whose product id is not worth pinning down.
const ANY: u16 = 0xFFFF;

/// The information required to grab a device.
///
#[derive(Hash, Eq, PartialEq, Clone, Debug)]
pub struct GrabTarget {
    pub name: String,
    pub bus: u16,
    pub vendor: u16,
    pub product: u16,
    /// The extra event codes that should be enabled for the device. This is so that the
    /// uinput device can input events that the original device didn't support.
    pub extra_event_codes: Vec<EventCode>,
}

impl GrabTarget {
    pub fn matches_device_info(&self, device_info: &EvdevDeviceInfo) -> bool {
        self.name_matches(&device_info.name)
            && (self.bus == ANY || self.bus == device_info.bus)
            && (self.vendor == ANY || self.vendor == device_info.vendor)
            && (self.product == ANY || self.product == device_info.product)
    }

    /// Name matching supports two wildcard forms, so a device whose enumerated name
    /// carries a variable HID instance number still matches:
    ///   "*Keyboard" -> suffix match ("RAPOO Keyboard-1 Keyboard" matches)
    ///   "RAPOO*"    -> prefix match
    /// Any other name is an exact match (the built-in "gpio_keys" target).
    fn name_matches(&self, name: &str) -> bool {
        if let Some(suffix) = self.name.strip_prefix('*') {
            name.ends_with(suffix)
        } else if let Some(prefix) = self.name.strip_suffix('*') {
            name.starts_with(prefix)
        } else {
            name == self.name
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn device(name: &str) -> EvdevDeviceInfo {
        EvdevDeviceInfo {
            name: name.to_string(),
            bus: 0x05,
            vendor: 0x245a,
            product: 0x8276,
            version: 0x0101,
        }
    }

    fn target(name: &str, bus: u16, vendor: u16, product: u16) -> GrabTarget {
        GrabTarget {
            name: name.to_string(),
            bus,
            vendor,
            product,
            extra_event_codes: Vec::new(),
        }
    }

    #[test]
    fn exact_name_still_matches() {
        let t = target("gpio_keys", 0x19, 1, 1);
        assert!(t.matches_device_info(&EvdevDeviceInfo {
            name: "gpio_keys".into(),
            bus: 0x19,
            vendor: 1,
            product: 1,
            version: 0,
        }));
        assert!(!t.matches_device_info(&device("gpio_keys")));
    }

    #[test]
    fn suffix_wildcard_matches_variable_instance_number() {
        let t = target("*Keyboard", 0x05, 0x245a, ANY);
        assert!(t.matches_device_info(&device("RAPOO Keyboard-1 Keyboard")));
        assert!(t.matches_device_info(&device("RAPOO Keyboard-7 Keyboard")));
        assert!(!t.matches_device_info(&device("RAPOO Keyboard-1 Mouse")));
        assert!(!t.matches_device_info(&device("RAPOO Keyboard-1 Consumer Control")));
    }

    #[test]
    fn prefix_wildcard_matches_family() {
        let t = target("RAPOO*", 0x05, 0x245a, ANY);
        assert!(t.matches_device_info(&device("RAPOO Keyboard-1 Keyboard")));
        assert!(t.matches_device_info(&device("RAPOO Keyboard-1 Mouse")));
    }

    #[test]
    fn any_sentinel_ignores_vendor_product() {
        let t = target("*Keyboard", ANY, ANY, ANY);
        assert!(t.matches_device_info(&device("Some Keyboard")));
    }
}

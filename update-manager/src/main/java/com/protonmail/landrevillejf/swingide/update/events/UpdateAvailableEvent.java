package com.protonmail.landrevillejf.swingide.update.events;

import com.protonmail.landrevillejf.swingide.update.UpdateInfo;
import lombok.Value;

@Value
public class UpdateAvailableEvent {
    UpdateInfo updateInfo;
    String currentVersion;
}

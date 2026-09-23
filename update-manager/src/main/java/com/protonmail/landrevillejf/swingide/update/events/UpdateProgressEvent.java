package com.protonmail.landrevillejf.swingide.update.events;

import lombok.Value;

@Value
public class UpdateProgressEvent {
    int percent;
    String status;
}

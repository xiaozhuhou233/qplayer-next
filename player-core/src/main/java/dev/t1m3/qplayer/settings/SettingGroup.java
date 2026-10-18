package dev.t1m3.qplayer.settings;

import java.util.List;

/** One settings card: the consecutive rows of a category that share a group id.
 *  Public fields, because the QML bridge reads Java members as fields. */
public final class SettingGroup {

    public final String id;
    public final List<SettingSpec> rows;

    SettingGroup(String id, List<SettingSpec> rows) {
        this.id = id;
        this.rows = rows;
    }
}

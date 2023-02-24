/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

public class ChangedWay2 extends ChangedFeature2
{
    long[] nodeIds;

    public ChangedWay2(long id, int version, int flags, String[] tags, long[] nodeIds)
    {
        super(id, version, flags, tags);
        this.nodeIds = nodeIds;
    }
}

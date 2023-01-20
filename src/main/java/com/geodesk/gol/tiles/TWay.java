/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;
import com.clarisma.common.pbf.PbfDecoder;
import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructOutputStream;
import com.geodesk.feature.match.TypeBits;

import java.io.IOException;
import java.nio.ByteBuffer;

import static com.geodesk.feature.store.FeatureFlags.*;

public class TWay extends TFeature2D<TWay.Body>
{
    private TNode[] featureNodes;

    public TWay(long id)
    {
        super(id);
        flags |= 1 << FEATURE_TYPE_BITS;
    }

    @Override public void readBody(TileReader reader)
    {
        ByteBuffer buf = reader.buf();
        int ppBody = location() + 28;
        int pBody = buf.getInt(ppBody) + ppBody;
        reader.checkPointer(pBody);
        body = new Body(reader, buf, pBody);
    }

    class Body extends Struct
    {
        private final byte[] encodedCoords;
        private final int[] tipDeltas;

        public Body(TileReader reader, ByteBuffer buf, int pBody)
        {
            PbfDecoder decoder = new PbfDecoder(buf, pBody);
            int nodeCount = (int) decoder.readVarint();
            while (nodeCount > 0)
            {
                decoder.readVarint();
                decoder.readVarint();
                nodeCount--;
            }
            int bodySize = decoder.pos() - pBody;
            encodedCoords = new byte[bodySize];
            buf.get(pBody, encodedCoords);
            int p = pBody - 4;
            if (isRelationMember())
            {
                relations = reader.readRelationTableIndirect(p);
                p -= 4;
                bodySize += 4;
                setAlignment(1);   // 2-byte (1 << 1)
            }
            if ((flags & WAYNODE_FLAG) != 0)
            {
                int pBefore = reader.readTable(p, 2, -1,
                    TypeBits.NODES & TypeBits.WAYNODE_FLAGGED, false);
                featureNodes = reader.getCurrentNodes();
                tipDeltas = reader.getCurrentTipDeltas();
                bodySize += p - pBefore;
                reader.resetTables();
                setAlignment(1);   // 2-byte (1 << 1)
            }
            else
            {
                tipDeltas = null;
            }
            setSize(bodySize);
            int anchor = bodySize - encodedCoords.length;
            setAnchor(anchor);
            setLocation(pBody - anchor);
        }

        @Override public void writeTo(StructOutputStream out) throws IOException
        {
            // TODO
        }
    }
}

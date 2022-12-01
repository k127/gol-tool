/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol;

import com.clarisma.common.cli.Application;
import com.clarisma.common.cli.Command;
import com.clarisma.common.cli.DefaultCommand;
import com.clarisma.common.util.Log;

import java.io.PrintWriter;

public class GolTool extends Application
{
    public static final String VERSION = "0.1.2d";

    @Override public String version()
    {
        return "gol " + VERSION;
    }

    @Override public String description()
    {
        return
            "gol - Build, manage and query Geographic Object Libraries\n\n" +
            "Usage: gol <command> [options]\n\n" +
            "Commands:\n\n" +
            "  build - Create a GOL from an OSM data file\n" +
            "  query - Perform a GOQL query\n" +
            "  info  - Obtain statistics\n" +
            "  load  - Load an existing tile set\n" +
            "  save  - Export tiles to a tile set\n" +
            "  check - Verify integrity\n" +
            "\n" +
            "Use \"gol help <command>\" for detailed documentation.";
    }

    /*
    @Override protected Command defaultCommand()
    {
        return new DefaultCommand(this);
    }
     */

    public static void main(String[] args) throws Exception
    {
        // for(String arg: args) Log.debug("Arg: '%s'", arg);
        /*
        PrintWriter out = new PrintWriter(System.out);
        out.println("Bavière");
        out.flush();
         */
        GolTool app = new GolTool();
        System.exit(app.run(null, args));
    }
}

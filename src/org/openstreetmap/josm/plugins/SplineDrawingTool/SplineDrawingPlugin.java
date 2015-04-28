package org.openstreetmap.josm.plugins.SplineDrawingTool;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.gui.IconToggleButton;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

/**
 * <strong>SplineDrawingPlugin</strong> is the main class for the <tt>spline-drawing-tool</tt> 
 * plugin.
 */
public class SplineDrawingPlugin extends Plugin{
    public static final double EPSILON = 0.0000000000001;
    public SplineDrawingPlugin(PluginInformation info) {
        super(info);
    }
    
    /**
     * Called when the JOSM map frame is created or destroyed. 
     */
    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {             
        if (oldFrame == null && newFrame != null) { // map frame added
            Main.map.addMapMode(new IconToggleButton(new DrawSplineAction(Main.map)));
        }
    }

}

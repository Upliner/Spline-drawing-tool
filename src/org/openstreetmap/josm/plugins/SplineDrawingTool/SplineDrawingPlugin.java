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
	//private final ArrayList<Relation> turnrestrictions = new ArrayList<Relation>();
	//public Dialog ToggleDialog;
	//private AreasAction exportAction;
	
    public SplineDrawingPlugin(PluginInformation info) {
        super(info);
        //WayGeneralizationAction plg= new WayGeneralizationAction();
        //MainMenu.add(Main.main.menu.moreToolsMenu, new MergeOverlapAction());
        //System.out.println(getPluginDir());
    }
    
    /**
     * Called when the JOSM map frame is created or destroyed. 
     */
    @Override
    
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {             
        if (oldFrame == null && newFrame != null) { // map frame added
        	
        	Main.map.addMapMode(new IconToggleButton(new DrawSplineAction(Main.map)));
//            WayGeneralizationAction.staticNode = new Node();
//            DataSet _dataSet = Main.map.mapView.getEditLayer().data;
//            _dataSet.addPrimitive(WayGeneralizationAction.staticNode);
//            System.out.println("ADDED NODE " + WayGeneralizationAction.staticNode);
			//sss=new AreasSelector(mapFrame);
        	
            //TurnRestrictionsListDialog dialog  = new TurnRestrictionsListDialog();
            //add the dialog
            //newFrame.addToggleDialog(dialog);
            //CreateOrEditTurnRestrictionAction.getInstance();
        }
    }

    /*@Override
    public PreferenceSetting getPreferenceSetting() {
        return new PreferenceEditor();
    }*/

}
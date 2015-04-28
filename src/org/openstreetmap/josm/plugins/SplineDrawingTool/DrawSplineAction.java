package org.openstreetmap.josm.plugins.SplineDrawingTool;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.plugins.SplineDrawingTool.SplineDrawingPlugin.EPSILON;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.MapView.LayerChangeListener;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.gui.util.ModifierListener;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.plugins.SplineDrawingTool.Spline.PointHandle;
import org.openstreetmap.josm.plugins.SplineDrawingTool.Spline.SegmentPoint;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

import static org.openstreetmap.josm.tools.I18n.marktr;

import java.awt.Point;

import javax.swing.AbstractAction;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.data.osm.visitor.paint.MapPaintSettings;
import org.openstreetmap.josm.data.osm.visitor.paint.PaintColors;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;

@SuppressWarnings("serial")
public  class DrawSplineAction extends MapMode implements MapViewPaintable, ModifierListener, LayerChangeListener{
    private final Cursor cursorJoinNode;
    private final Cursor cursorJoinWay;

    private Color rubberLineColor;

    private final Shortcut backspaceShortcut;
    private final BackSpaceAction backspaceAction;

    public DrawSplineAction(MapFrame mapFrame)
    {  
        super(
                tr("Spline drawing"),  // name
                "spline2",       // icon name 
                tr("Draw a spline curve"), // tooltip 
                mapFrame, getCursor()
        );
        
        backspaceShortcut = Shortcut.registerShortcut("mapmode:backspace",
                tr("Backspace in Add mode"), KeyEvent.VK_BACK_SPACE, Shortcut.DIRECT);
        backspaceAction = new BackSpaceAction();
        cursorJoinNode = ImageProvider.getCursor("crosshair", "joinnode");
        cursorJoinWay = ImageProvider.getCursor("crosshair", "joinway");
        MapView.addLayerChangeListener(this);
        readPreferences();
    }

    private static Cursor getCursor() {
        try {
            return ImageProvider.getCursor("crosshair", "spline");
        } catch (Exception e) {
            Main.error(e);
        }
        return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
    }

    @Override
    public void enterMode() {
        if (!isEnabled())
            return;
        super.enterMode();
        readPreferences();

        Main.registerActionShortcut(backspaceAction, backspaceShortcut);

        Main.map.mapView.addMouseListener(this);
        Main.map.mapView.addMouseMotionListener(this);
        Main.map.mapView.addTemporaryLayer(this);

        Main.map.keyDetector.addModifierListener(this);
    }
    int initialMoveDelay;

    private void readPreferences() {
        rubberLineColor = Main.pref.getColor(marktr("helper line"), null);
        if (rubberLineColor == null) rubberLineColor = PaintColors.SELECTED.get();

        initialMoveDelay = Main.pref.getInteger("edit.initial-move-delay", 200);
    }

    @Override
    public void exitMode() {
        super.exitMode();
        Main.map.mapView.removeMouseListener(this);
        Main.map.mapView.removeMouseMotionListener(this);
        Main.map.mapView.removeTemporaryLayer(this);
        Main.unregisterActionShortcut(backspaceAction, backspaceShortcut);

        Main.map.statusLine.activateAnglePanel(false);
        removeHighlighting();
        Main.map.keyDetector.removeModifierListener(this);
    }

    @Override
    public void modifiersChanged(int modifiers) {
        updateKeyModifiers(modifiers);
    }
   
    private Long mouseDownTime;
    private PointHandle ph;
    private Point helperEndpoint;
    public int index=0;
    boolean lockCounterpart;
    private MoveCommand mc;
    private boolean dragControl;
    @Override
    public void mousePressed(MouseEvent e) {
        mouseDownTime = null;
        updateKeyModifiers(e);
        if (e.getButton() != MouseEvent.BUTTON1)
            return;
        if(!Main.map.mapView.isActiveLayerDrawable())
            return;
        Spline spl = getSpline();
        if (spl == null)
        	return;
        helperEndpoint = null;
        if (e.getClickCount() == 2) {
            spl.finishSpline();
            Main.map.repaint();
            return;
        }
        dragControl = false;
        mouseDownTime = System.currentTimeMillis();
        ph = spl.getNearestPoint(Main.map.mapView, e.getPoint());
        if (ph != null) {
            if (ctrl) {
                if (ph.point == SegmentPoint.ENDPOINT) {
                    ph = new PointHandle(ph.segm, SegmentPoint.CONTROL_NEXT);
                    lockCounterpart = true;
                } else
                    lockCounterpart = false;
            } else {
                lockCounterpart = (ph.point != SegmentPoint.ENDPOINT
                        && Math.abs(ph.segm.cprev.east()+ph.segm.cnext.east()) < EPSILON
                        && Math.abs(ph.segm.cprev.north()+ph.segm.cnext.north()) < EPSILON
                        ); 
            }
            if (ph.point == SegmentPoint.ENDPOINT && !Main.main.undoRedo.commands.isEmpty()) {
                Command cmd = Main.main.undoRedo.commands.getLast();
                if (cmd instanceof MoveCommand) {
                    mc = (MoveCommand)cmd;
                    Collection<Node> pp = mc.getParticipatingPrimitives();
                    if (pp.size() != 1 || !pp.contains(ph.segm.point))
                        mc = null;
                    else
                        mc.changeStartPoint(ph.segm.point.getEastNorth());
                }
            }
            if (ph.point != SegmentPoint.ENDPOINT && !Main.main.undoRedo.commands.isEmpty()) {
                Command cmd = Main.main.undoRedo.commands.getLast();
                if (!(cmd instanceof Spline.EditSplineCommand && ((Spline.EditSplineCommand)cmd).segm == ph.segm))
                	dragControl = true;
            }
            return;
        }
        Node n = null;
        boolean existing = false;
        if (!ctrl) {
            n = Main.map.mapView.getNearestNode(e.getPoint(), OsmPrimitive.isUsablePredicate);
            existing = true;
        }
        if (n == null) {
            n = new Node(Main.map.mapView.getLatLon(e.getX(), e.getY()));
            existing = false;
        }
        Main.main.undoRedo.add(spl.new AddSplineNodeCommand(new Spline.Segment(n), existing));
        ph = new PointHandle(spl.segments.getLast(), SegmentPoint.CONTROL_NEXT);
        lockCounterpart = true;
        Main.map.repaint();
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        mc = null;
        mouseDownTime = null;
        mouseMoved(e);
    }
    
    @Override
    public void mouseDragged(MouseEvent e) {
        updateKeyModifiers(e);
        if(mouseDownTime == null) return;
        if(!Main.map.mapView.isActiveLayerDrawable()) return;
        if (System.currentTimeMillis() - mouseDownTime < initialMoveDelay) return;
        if (ph == null) return;
        Spline spl = getSpline();
        if (spl == null) return;
        if (spl.segments.isEmpty()) return;
        EastNorth en = Main.map.mapView.getEastNorth(e.getX(), e.getY());
        if (Main.getProjection().eastNorth2latlon(en).isOutSideWorld())
            return;
        if (ph.point == SegmentPoint.ENDPOINT) {
            if (mc == null) {
                mc = new MoveCommand(ph.segm.point, ph.segm.point.getEastNorth(), en);
                Main.main.undoRedo.add(mc);
            } else {
                mc.applyVectorTo(en);
            }
        } else {
        	if (dragControl) {
        		Main.main.undoRedo.add(spl.new EditSplineCommand(ph.segm));
        		dragControl = false;
        	}
            ph.movePoint(en);
            if (lockCounterpart) {
                if (ph.point == SegmentPoint.CONTROL_NEXT)
                    ph.segm.cprev = ph.segm.cnext.sub(new EastNorth(0,0));
                else if (ph.point == SegmentPoint.CONTROL_PREV)
                    ph.segm.cnext = ph.segm.cprev.sub(new EastNorth(0,0));
            }
        }
        Main.map.repaint();
    }
    Node nodeHighlight;
    @Override
    public void mouseMoved(MouseEvent e) {
        updateKeyModifiers(e);
        if(!Main.map.mapView.isActiveLayerDrawable())
            return;
        removeHighlighting();
        Spline spl = getSpline();
        if (spl == null)
        	return;
        ph = spl.getNearestPoint(Main.map.mapView, e.getPoint());
        if (ph == null) {
            Node n = null;
            if (!ctrl)
                n = Main.map.mapView.getNearestNode(e.getPoint(), OsmPrimitive.isUsablePredicate);
            if (n == null) {
                helperEndpoint = e.getPoint();
                Main.map.mapView.setNewCursor(cursor, this);
            } else {
                setHighlight(n);
                Main.map.mapView.setNewCursor(cursorJoinNode, this);
                helperEndpoint = Main.map.mapView.getPoint(n);
            }
        } else {
            helperEndpoint = null;
            Main.map.mapView.setNewCursor(cursorJoinWay, this);
            if (ph.point == SegmentPoint.ENDPOINT)
                setHighlight(ph.segm.point);
        }
        Main.map.repaint();
    }

    /**
     * Repaint on mouse exit so that the helper line goes away.
     */
    @Override
    public void mouseExited(MouseEvent e) {
        if(!Main.map.mapView.isActiveLayerDrawable())
            return;
        removeHighlighting();
        helperEndpoint = null;
        Main.map.mapView.repaint();
    }

    private void setHighlight(Node n) {
        nodeHighlight = n;
        n.setHighlighted(true);
    }
    /**
     * Removes target highlighting from primitives. Issues repaint if required.
     * Returns true if a repaint has been issued.
     */
    private boolean removeHighlighting() {
        if (nodeHighlight != null) {
            nodeHighlight.setHighlighted(false);
            nodeHighlight = null;
            return true;
        }
        return false;
    }

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds box) {
    	Spline spl = getSpline();
    	if (spl == null)
    		return;
        spl.paint(g, mv, rubberLineColor, Color.green, helperEndpoint);
        if (ph != null && ph.point != SegmentPoint.ENDPOINT) {
            g.setColor(MapPaintSettings.INSTANCE.getSelectedColor());;
            Point p = mv.getPoint(ph.getPoint());
            g.fillRect(p.x - 1, p.y - 1, 3, 3);
        }
    }

    @Override
    public boolean layerIsSupported(Layer l) {
        return l instanceof OsmDataLayer;
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(getEditLayer() != null);
    }

    public class BackSpaceAction extends AbstractAction {
        @Override
        public void actionPerformed(ActionEvent e) {
            Main.main.undoRedo.undo();
        }
    }
    private Spline splCached;
    Spline getSpline() {
    	if (splCached != null)
    		return splCached;
    	Layer l = Main.main.getEditLayer();
    	if (!(l instanceof OsmDataLayer))
    		return null;
    	splCached = layerSplines.get(l);
		if (splCached == null)
		    splCached = new Spline();
    	layerSplines.put(l, splCached);
    	return splCached;
    }
	@Override
	public void activeLayerChange(Layer oldLayer, Layer newLayer) {
		splCached= layerSplines.get(newLayer);
	}
	Map<Layer, Spline> layerSplines = new HashMap<>();

	@Override
	public void layerAdded(Layer newLayer) {
	}

	@Override
	public void layerRemoved(Layer oldLayer) {
		layerSplines.remove(oldLayer);
		splCached = null;
	}
}


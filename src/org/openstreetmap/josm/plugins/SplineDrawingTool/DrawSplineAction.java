package org.openstreetmap.josm.plugins.SplineDrawingTool;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.plugins.SplineDrawingTool.SplineDrawingPlugin.EPSILON;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.gui.util.KeyPressReleaseListener;
import org.openstreetmap.josm.gui.util.ModifierListener;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.SelectionChangedListener;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.SplineDrawingTool.Spline.PointHandle;
import org.openstreetmap.josm.plugins.SplineDrawingTool.Spline.SegmentPoint;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.Point;
import java.util.Collections;
import java.util.List;

import javax.swing.AbstractAction;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.WaySegment;
import org.openstreetmap.josm.data.osm.visitor.paint.MapPaintSettings;
import org.openstreetmap.josm.data.osm.visitor.paint.PaintColors;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.tools.Utils;

@SuppressWarnings("serial")
public  class DrawSplineAction extends MapMode implements MapViewPaintable, SelectionChangedListener, KeyPressReleaseListener, ModifierListener{
	/**
	 * 
	 */
	private static final long serialVersionUID = -5953561850680258070L;
	private final Cursor cursorJoinNode;
    private final Cursor cursorJoinWay;

    private Node lastUsedNode = null;

    private Node mouseOnExistingNode;
    private Set<Way> mouseOnExistingWays = new HashSet<>();
    // old highlights store which primitives are currently highlighted. This
    // is true, even if target highlighting is disabled since the status bar
    // derives its information from this list as well.
    private Set<OsmPrimitive> oldHighlights = new HashSet<>();
    // new highlights contains a list of primitives that should be highlighted
    // but haven’t been so far. The idea is to compare old and new and only
    // repaint if there are changes.
    private Set<OsmPrimitive> newHighlights = new HashSet<>();
    private boolean drawHelperLine;
    private boolean wayIsFinished = false;
    private boolean drawTargetHighlight;
    private Point mousePos;
    private Color rubberLineColor;

    private Node currentBaseNode;
    private Node previousNode;
    private final Shortcut backspaceShortcut;
    private final BackSpaceAction backspaceAction;
    private final Shortcut snappingShortcut;
    private boolean ignoreNextKeyRelease;

    private boolean useRepeatedShortcut;
    private static final BasicStroke BASIC_STROKE = new BasicStroke(1);

    private static int snapToIntersectionThreshold;
	private static Node n1;

	public DrawSplineAction(MapFrame mapFrame)
	{  
		super(
				tr("Spline drawing"),  // name
				"spline2.png",       // icon name 
				tr("Draw an spline curve"), // tooltip 
				mapFrame, getCursor()
		);
		
        snappingShortcut = Shortcut.registerShortcut("mapmode:drawanglesnapping",
                tr("Mode: Draw Angle snapping"), KeyEvent.CHAR_UNDEFINED, Shortcut.NONE);
        backspaceShortcut = Shortcut.registerShortcut("mapmode:backspace",
                tr("Backspace in Add mode"), KeyEvent.VK_BACK_SPACE, Shortcut.DIRECT);
        backspaceAction = new BackSpaceAction();
        cursorJoinNode = ImageProvider.getCursor("crosshair", "joinnode");
        cursorJoinWay = ImageProvider.getCursor("crosshair", "joinway");
        
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


    /**
     * Checks if a map redraw is required and does so if needed. Also updates the status bar
     */
    private boolean redrawIfRequired() {
        updateStatusLine();
        // repaint required if the helper line is active.
        boolean needsRepaint = drawHelperLine && !wayIsFinished;
        if(drawTargetHighlight) {
            // move newHighlights to oldHighlights; only update changed primitives
            for(OsmPrimitive x : newHighlights) {
                if(oldHighlights.contains(x)) {
                    continue;
                }
                x.setHighlighted(true);
                needsRepaint = true;
            }
            oldHighlights.removeAll(newHighlights);
            for(OsmPrimitive x : oldHighlights) {
                x.setHighlighted(false);
                needsRepaint = true;
            }
        }
        // required in order to print correct help text
        oldHighlights = newHighlights;

        if (!needsRepaint && !drawTargetHighlight)
            return false;

        // update selection to reflect which way being modified
        DataSet currentDataSet = getCurrentDataSet();
        if (currentBaseNode != null && currentDataSet != null && !currentDataSet.getSelected().isEmpty()) {
            Way continueFrom = getWayForNode(currentBaseNode);
            if (alt && continueFrom != null && (!currentBaseNode.isSelected() || continueFrom.isSelected())) {
                addRemoveSelection(currentDataSet, currentBaseNode, continueFrom);
                needsRepaint = true;
            } else if (!alt && continueFrom != null && !continueFrom.isSelected()) {
                currentDataSet.addSelected(continueFrom);
                needsRepaint = true;
            }
        }

        if(needsRepaint) {
            Main.map.mapView.repaint();
        }
        return needsRepaint;
    }

    private static void addRemoveSelection(DataSet ds, OsmPrimitive toAdd, OsmPrimitive toRemove) {
        ds.beginUpdate(); // to prevent the selection listener to screw around with the state
        ds.addSelected(toAdd);
        ds.clearSelection(toRemove);
        ds.endUpdate();
    }

    @Override
    public void enterMode() {
        if (!isEnabled())
            return;
        super.enterMode();
        readPreferences();

        // determine if selection is suitable to continue drawing. If it
        // isn't, set wayIsFinished to true to avoid superfluous repaints.
        determineCurrentBaseNodeAndPreviousNode(getCurrentDataSet().getSelected());
        wayIsFinished = currentBaseNode == null;

        //toleranceMultiplier = 0.01 * NavigatableComponent.PROP_SNAP_DISTANCE.get();

        Main.registerActionShortcut(backspaceAction, backspaceShortcut);

        Main.map.mapView.addMouseListener(this);
        Main.map.mapView.addMouseMotionListener(this);
        Main.map.mapView.addTemporaryLayer(this);
        DataSet.addSelectionListener(this);

        Main.map.keyDetector.addKeyListener(this);
        Main.map.keyDetector.addModifierListener(this);
        ignoreNextKeyRelease = true;
        // would like to but haven't got mouse position yet:
        // computeHelperLine(false, false, false);
    }
    int initialMoveDelay;

    private void readPreferences() {
        rubberLineColor = Main.pref.getColor(marktr("helper line"), null);
        if (rubberLineColor == null) rubberLineColor = PaintColors.SELECTED.get();

        drawHelperLine = Main.pref.getBoolean("draw.helper-line", true);
        drawTargetHighlight = Main.pref.getBoolean("draw.target-highlight", true);
        snapToIntersectionThreshold = Main.pref.getInteger("edit.snap-intersection-threshold",10);
        initialMoveDelay = Main.pref.getInteger("edit.initial-move-delay", 200);
    }

    @Override
    public void exitMode() {
        super.exitMode();
        Main.map.mapView.removeMouseListener(this);
        Main.map.mapView.removeMouseMotionListener(this);
        Main.map.mapView.removeTemporaryLayer(this);
        DataSet.removeSelectionListener(this);
        Main.unregisterActionShortcut(backspaceAction, backspaceShortcut);

        Main.map.statusLine.activateAnglePanel(false);
        removeHighlighting();
        Main.map.keyDetector.removeKeyListener(this);
        Main.map.keyDetector.removeModifierListener(this);

        // when exiting we let everybody know about the currently selected
        // primitives
        //
        DataSet ds = getCurrentDataSet();
        if(ds != null) {
            ds.fireSelectionChanged();
        }
    }

    /**
     * redraw to (possibly) get rid of helper line if selection changes.
     */
    @Override
    public void modifiersChanged(int modifiers) {
        if (!Main.isDisplayingMapView() || !Main.map.mapView.isActiveLayerDrawable())
            return;
        updateKeyModifiers(modifiers);
        computeHelperLine();
    }

    @Override
    public void doKeyPressed(KeyEvent e) {
        if (!snappingShortcut.isEvent(e) && !(useRepeatedShortcut && getShortcut().isEvent(e)))
            return;
        computeHelperLine();
        redrawIfRequired();
    }

    @Override
    public void doKeyReleased(KeyEvent e) {
    	System.out.println("KeyRel");
    	index=0;
        if (!snappingShortcut.isEvent(e) && !(useRepeatedShortcut && getShortcut().isEvent(e)))
            return;
        if (ignoreNextKeyRelease) {
            ignoreNextKeyRelease = false;
            return;
        }
        computeHelperLine();
        redrawIfRequired();
    }

    /**
     * redraw to (possibly) get rid of helper line if selection changes.
     */
    @Override
    public void selectionChanged(Collection<? extends OsmPrimitive> newSelection) {
        if(!Main.map.mapView.isActiveLayerDrawable())
            return;
        computeHelperLine();
    }


    
    private Long mouseDownTime;
    private PointHandle ph;
    private Point helperEndpoint;
    public int index=0;
    boolean lockCounterpart;
    private Spline spl = new Spline();
    private MoveCommand mc;
    @Override
    public void mousePressed(MouseEvent e) {
        mouseDownTime = null;
        updateKeyModifiers(e);
    	if (e.getButton() != MouseEvent.BUTTON1)
            return;
        if(!Main.map.mapView.isActiveLayerDrawable())
            return;
		helperEndpoint = null;
    	if (e.getClickCount() == 2) {
    		spl.finishSpline(10);
            Main.map.repaint();
    		return;
    	}
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
    		return;
    	}
    	Node n = null;
    	if (!ctrl)
    	    n = Main.map.mapView.getNearestNode(e.getPoint(), OsmPrimitive.isUsablePredicate);
        if (n == null) {
            n = new Node(Main.map.mapView.getLatLon(e.getX(), e.getY()));
            Main.main.undoRedo.add(new AddCommand(n));
        }
    	spl.segments.add(new Spline.Segment(n));
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
        if(mouseDownTime == null)
            return;
        if(!Main.map.mapView.isActiveLayerDrawable())
            return;
    	if (spl.segments.isEmpty())
    		return;
        if (System.currentTimeMillis() - mouseDownTime < initialMoveDelay)
            return;
        if (ph == null) 
        	return;
        EastNorth en = Main.map.mapView.getEastNorth(e.getX(), e.getY());
        if (ph.point == SegmentPoint.ENDPOINT) {
        	if (mc == null) {
        		mc = new MoveCommand(ph.segm.point, ph.segm.point.getEastNorth(), en);
        		Main.main.undoRedo.add(mc);
        	} else {
        		mc.applyVectorTo(en);
        	}
        } else {
            ph.movePoint(en);
        }
        if (lockCounterpart) {
        	if (ph.point == SegmentPoint.CONTROL_NEXT)
        		ph.segm.cprev = ph.segm.cnext.sub(new EastNorth(0,0));
        	else if (ph.point == SegmentPoint.CONTROL_PREV)
        		ph.segm.cnext = ph.segm.cprev.sub(new EastNorth(0,0));
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
     * This method prepares data required for painting the "helper line" from
     * the last used position to the mouse cursor. It duplicates some code from
     * mouseReleased() (FIXME).
     */
    private void computeHelperLine() {
        MapView mv = Main.map.mapView;
        if (mousePos == null) {
            currentBaseNode = null;
            return;
        }

        Collection<OsmPrimitive> selection = getCurrentDataSet().getSelected();

        Node currentMouseNode = null;
        mouseOnExistingNode = null;
        mouseOnExistingWays = new HashSet<>();

        if (!ctrl && mousePos != null) {
            currentMouseNode = mv.getNearestNode(mousePos, OsmPrimitive.isSelectablePredicate);
        }

        // We need this for highlighting and we'll only do so if we actually want to re-use
        // *and* there is no node nearby (because nodes beat ways when re-using)
        if(!ctrl && currentMouseNode == null) {
            List<WaySegment> wss = mv.getNearestWaySegments(mousePos, OsmPrimitive.isSelectablePredicate);
            for(WaySegment ws : wss) {
                mouseOnExistingWays.add(ws.way);
            }
        }

        if (currentMouseNode != null) {
            // user clicked on node
            if (selection.isEmpty()) return;
            currentMouseNode.getEastNorth();
            mouseOnExistingNode = currentMouseNode;
        } else {
            mv.getEastNorth(mousePos.x, mousePos.y);
        }

        determineCurrentBaseNodeAndPreviousNode(selection);

        if (currentBaseNode == null || currentBaseNode == currentMouseNode)
            return; // Don't create zero length way segments.

        // status bar was filled by snapHelper
    }

    /**
     * Helper function that sets fields currentBaseNode and previousNode
     * @param selection
     * uses also lastUsedNode field
     */
    private void determineCurrentBaseNodeAndPreviousNode(Collection<OsmPrimitive>  selection) {
        Node selectedNode = null;
        Way selectedWay = null;
        for (OsmPrimitive p : selection) {
            if (p instanceof Node) {
                if (selectedNode != null)
                    return;
                selectedNode = (Node) p;
            } else if (p instanceof Way) {
                if (selectedWay != null)
                    return;
                selectedWay = (Way) p;
            }
        }
        // we are here, if not more than 1 way or node is selected,
        // the node from which we make a connection
        
        currentBaseNode = null;
        previousNode = null;

        // Try to find an open way to measure angle from it. The way is not to be continued!
        // warning: may result in changes of currentBaseNode and previousNode
        // please remove if bugs arise
        if (selectedWay == null && selectedNode != null) {
            for (OsmPrimitive p: selectedNode.getReferrers()) {
                if (p.isUsable() && p instanceof Way && ((Way) p).isFirstLastNode(selectedNode)) {
                    if (selectedWay!=null) { // two uncontinued ways, nothing to take as reference
                        selectedWay=null;
                        break;
                    } else {
                        // set us ~continue this way (measure angle from it)
                        selectedWay = (Way) p;
                    }
                }
            }
        }

        if (selectedNode == null) {
            if (selectedWay == null)
                return;
            continueWayFromNode(selectedWay, lastUsedNode);
        } else if (selectedWay == null) {
            currentBaseNode = selectedNode;
        } else if (!selectedWay.isDeleted()) { // fix #7118
            continueWayFromNode(selectedWay, selectedNode);
            if(index>1){
            	continueWayFromNodeSpline(selectedWay, selectedNode);
            }
        }
    }

    /**
     * if one of the ends of @param way is given @param node ,
     * then set  currentBaseNode = node and previousNode = adjacent node of way
     */
    private void continueWayFromNode(Way way, Node node) {
        int n = way.getNodesCount();
        if (node == way.firstNode()){
            currentBaseNode = node;
            if (n>1) previousNode = way.getNode(1);
        } else if (node == way.lastNode()) {
            currentBaseNode = node;
            if (n>1) previousNode = way.getNode(n-2);
        }
        
    }
    
    /**
     * if one of the ends of @param way is given @param node ,
     * then set  currentBaseNode = node and previousNode = adjacent node of way
     */
    private void continueWayFromNodeSpline(Way way, Node node) {
        int n = way.getNodesCount();
        if (node == way.firstNode()){
            currentBaseNode = node;
            if (n>1) previousNode = way.getNode(1);
        } else if (node == way.lastNode()) {
            currentBaseNode = node;
            if (n>1) previousNode = way.getNode(n-2);
        }
        if(n>1){
        	double xm=previousNode.getCoor().lat();
        	double ym=previousNode.getCoor().lon();
        	double x1=currentBaseNode.getCoor().lat();
    		double y1=currentBaseNode.getCoor().lon();
    		double x2=2*xm-x1;
    		double y2=2*ym-y1;
    		 n1=new Node(new LatLon(x2, y2));
        	
        }
    }

    /**
     * Repaint on mouse exit so that the helper line goes away.
     */
    @Override
    public void mouseExited(MouseEvent e) {
        if(!Main.map.mapView.isActiveLayerDrawable())
            return;
        mousePos = e.getPoint();
        boolean repaintIssued = removeHighlighting();
        // force repaint in case snapHelper needs one. If removeHighlighting
        // caused one already, don’t do it again.
        if(!repaintIssued) {
            Main.map.mapView.repaint();
        }
    }

    /**
     * @return If the node is the end of exactly one way, return this.
     *  <code>null</code> otherwise.
     */
    public static Way getWayForNode(Node n) {
        Way way = null;
        for (Way w : Utils.filteredCollection(n.getReferrers(), Way.class)) {
            if (!w.isUsable() || w.getNodesCount() < 1) {
                continue;
            }
            Node firstNode = w.getNode(0);
            Node lastNode = w.getNode(w.getNodesCount() - 1);
            if ((firstNode == n || lastNode == n) && (firstNode != lastNode)) {
                if (way != null)
                    return null;
                way = w;
            }
        }
        return way;
    }

    public Node getCurrentBaseNode() {
        return currentBaseNode;
    }

    // helper for adjustNode
    static double det(double a, double b, double c, double d) {
        return a * d - b * c;
    }


    /**
     * Removes target highlighting from primitives. Issues repaint if required.
     * Returns true if a repaint has been issued.
     */
    private void setHighlight(Node n) {
    	//removeHighlighting();
    	nodeHighlight = n;
    	n.setHighlighted(true);
    }
    private boolean removeHighlighting() {
    	if (nodeHighlight != null) {
    		nodeHighlight.setHighlighted(false);
    		nodeHighlight = null;
    		return true;
    	}
    	return false;
        //newHighlights = new HashSet<>();
        //return redrawIfRequired();
    }

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds box) {
    	spl.paint(g, mv, rubberLineColor, Color.green, helperEndpoint);
    	if (ph != null && ph.point != SegmentPoint.ENDPOINT) {
    		g.setColor(MapPaintSettings.INSTANCE.getSelectedColor());;
    		Point p = mv.getPoint(ph.getPoint());
    		g.fillRect(p.x - 1, p.y - 1, 3, 3);
    	}
    }

    @Override
    public String getModeHelpText() {
        StringBuilder rv;
        /*
         *  No modifiers: all (Connect, Node Re-Use, Auto-Weld)
         *  CTRL: disables node re-use, auto-weld
         *  Shift: do not make connection
         *  ALT: make connection but start new way in doing so
         */

        /*
         * Status line text generation is split into two parts to keep it maintainable.
         * First part looks at what will happen to the new node inserted on click and
         * the second part will look if a connection is made or not.
         *
         * Note that this help text is not absolutely accurate as it doesn't catch any special
         * cases (e.g. when preventing <---> ways). The only special that it catches is when
         * a way is about to be finished.
         *
         * First check what happens to the new node.
         */

        // oldHighlights stores the current highlights. If this
        // list is empty we can assume that we won't do any joins
        if (ctrl || oldHighlights.isEmpty()) {
            rv = new StringBuilder(tr("Create new node."));
        } else {
            // oldHighlights may store a node or way, check if it's a node
            OsmPrimitive x = oldHighlights.iterator().next();
            if (x instanceof Node) {
                rv = new StringBuilder(tr("Select node under cursor."));
            } else {
                rv = new StringBuilder(trn("Insert new node into way.", "Insert new node into {0} ways.",
                        oldHighlights.size(), oldHighlights.size()));
            }
        }

        /*
         * Check whether a connection will be made
         */
        if (currentBaseNode != null && !wayIsFinished) {
            if (alt) {
                rv.append(" ").append(tr("Start new way from last node."));
            } else {
                rv.append(" ").append(tr("Continue way from last node."));
            }
        }

        Node n = mouseOnExistingNode;
        /*
         * Handle special case: Highlighted node == selected node => finish drawing
         */
        if (n != null && getCurrentDataSet() != null && getCurrentDataSet().getSelectedNodes().contains(n)) {
            if (wayIsFinished) {
                rv = new StringBuilder(tr("Select node under cursor."));
            } else {
                rv = new StringBuilder(tr("Finish drawing."));
            }
        }

        /*
         * Handle special case: Self-Overlapping or closing way
         */
        if (getCurrentDataSet() != null && !getCurrentDataSet().getSelectedWays().isEmpty() && !wayIsFinished && !alt) {
            Way w = getCurrentDataSet().getSelectedWays().iterator().next();
            for (Node m : w.getNodes()) {
                if (m.equals(mouseOnExistingNode) || mouseOnExistingWays.contains(w)) {
                    rv.append(" ").append(tr("Finish drawing."));
                    break;
                }
            }
        }
        return rv.toString();
    }

    /**
     * Get selected primitives, while draw action is in progress.
     *
     * While drawing a way, technically the last node is selected.
     * This is inconvenient when the user tries to add/edit tags to the way.
     * For this case, this method returns the current way as selection,
     * to work around this issue.
     * Otherwise the normal selection of the current data layer is returned.
     */
    public Collection<OsmPrimitive> getInProgressSelection() {
        DataSet ds = getCurrentDataSet();
        if (ds == null) return null;
        if (currentBaseNode != null && !ds.getSelected().isEmpty()) {
            Way continueFrom = getWayForNode(currentBaseNode);
            if (continueFrom != null)
                return Collections.<OsmPrimitive>singleton(continueFrom);
        }
        return ds.getSelected();
    }

    @Override
    public boolean layerIsSupported(Layer l) {
        return l instanceof OsmDataLayer;
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(getEditLayer() != null);
    }

    @Override
    public void destroy() {
        super.destroy();
    }

    public class BackSpaceAction extends AbstractAction {

        @Override
        public void actionPerformed(ActionEvent e) {
            Main.main.undoRedo.undo();
            Node n=null;
            Command lastCmd=Main.main.undoRedo.commands.peekLast();
            if (lastCmd==null) return;
            for (OsmPrimitive p: lastCmd.getParticipatingPrimitives()) {
                if (p instanceof Node) {
                    if (n==null) {
                        n=(Node) p; // found one node
                        wayIsFinished=false;
                    }  else {
                        // if more than 1 node were affected by previous command,
                        // we have no way to continue, so we forget about found node
                        n=null;
                        break;
                    }
                }
            }
            // select last added node - maybe we will continue drawing from it
            if (n!=null) {
                getCurrentDataSet().addSelected(n);
            }
        }
    }
}


/**
 * 
 */
package org.openstreetmap.josm.plugins.SplineDrawingTool;

//import static org.openstreetmap.josm.plugins.contourmerge.util.Assert.checkArgNotNull;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.plugins.SplineDrawingTool.SplineDrawingPlugin.EPSILON;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import javax.swing.JOptionPane;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.preferences.IntegerProperty;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.NavigatableComponent;

public class Spline {
    public static IntegerProperty PROP_SPLINEPOINTS = new IntegerProperty("edit.spline.num_points", 10);
    public static class Segment {
        public Node node; // Endpoint
        public EastNorth cprev, cnext; // Relative offsets of control points
        public Segment(Node node) {
            this.node = node;
            cprev = new EastNorth(0, 0);
            cnext = new EastNorth(0, 0);
        }
    }
    private final LinkedList<Segment> segments = new LinkedList<Segment>();
    public Segment getFirstSegment() {
    	Iterator<Segment> it = segments.iterator();
    	while (it.hasNext()) {
    		Segment segm = it.next();
    		if (!segm.node.isDeleted())
    			return segm;
    	}
    	return null;
    }
    public Segment getLastSegment() {
    	Iterator<Segment> it = segments.descendingIterator();
    	while (it.hasNext()) {
    		Segment segm = it.next();
    		if (!segm.node.isDeleted())
    			return segm;
    	}
    	return null;
    }
    public void paint(Graphics2D g, MapView mv, Color curveColor, Color ctlColor, Point helperEndpoint) {
        if (segments.isEmpty())
            return;
        final GeneralPath curv = new GeneralPath();
        final GeneralPath ctl = new GeneralPath();

        Point2D cbPrev = null;
        for (Segment segm : segments) {
        	if (segm.node.isDeleted())
        		continue;
            Point2D pt = mv.getPoint2D(segm.node);
            EastNorth en = segm.node.getEastNorth();

            Point2D ca = mv.getPoint2D(en.add(segm.cprev));
            Point2D cb = mv.getPoint2D(en.add(segm.cnext));

            ctl.moveTo(ca.getX(), ca.getY());
            ctl.lineTo(pt.getX(), pt.getY());
            ctl.lineTo(cb.getX(), cb.getY());

            if (cbPrev == null)
                curv.moveTo(pt.getX(), pt.getY());
            else 
                curv.curveTo(cbPrev.getX(), cbPrev.getY(), ca.getX(), ca.getY(), pt.getX(), pt.getY());
            cbPrev = cb;
        }
        if (helperEndpoint != null) {
            curv.curveTo(cbPrev.getX(), cbPrev.getY(), helperEndpoint.getX(), helperEndpoint.getY(), helperEndpoint.getX(), helperEndpoint.getY());
        }
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(curveColor);
        g.draw(curv);
        g.setStroke(new BasicStroke(1));
        g.setColor(ctlColor);
        g.draw(ctl);
    }
    public enum SegmentPoint { ENDPOINT, CONTROL_PREV, CONTROL_NEXT }; 
    public static class PointHandle {
        public final Segment segm;
        public final SegmentPoint point;
        public PointHandle(Segment segm, SegmentPoint point) {
            if (point == null)
                throw new IllegalArgumentException("Invalid SegmentPoint passed for PointHandle contructor");
            this.segm = segm;
            this.point = point;
        }
        public EastNorth getPoint() {
            EastNorth en = segm.node.getEastNorth();
            switch (point) {
            case ENDPOINT: return en; 
            case CONTROL_PREV: return en.add(segm.cprev); 
            case CONTROL_NEXT: return en.add(segm.cnext); 
            }
            throw new AssertionError();
        }
        public void movePoint(EastNorth en) {
            switch (point) {
            case ENDPOINT: segm.node.setEastNorth(en); return;
            case CONTROL_PREV: segm.cprev = segm.node.getEastNorth().sub(en); return;
            case CONTROL_NEXT: segm.cnext = segm.node.getEastNorth().sub(en); return;
            }
            throw new AssertionError();
        }
    }
    public boolean isEmpty() {
    	for (Segment segm : segments)
    		if (!segm.node.isDeleted())
    			return false;
    	return true;
    }
    public PointHandle getNearestPoint(MapView mv, Point2D point) {
        PointHandle bestPH = null;
        double bestDistSq =  NavigatableComponent.PROP_SNAP_DISTANCE.get();
        bestDistSq = bestDistSq * bestDistSq; 
        for (Segment segm : segments) {
        	if (segm.node.isDeleted()) continue;
            for(SegmentPoint sp : SegmentPoint.values()) {
                PointHandle ph = new PointHandle(segm, sp);
                double distSq = point.distanceSq(mv.getPoint2D(ph.getPoint()));
                if (distSq < bestDistSq) {
                    bestPH = ph;
                    bestDistSq = distSq;
                }
            }
        }
        return bestPH;
    }
    public void finishSpline() {
    	int detail = PROP_SPLINEPOINTS.get();
        Way w = new Way();
        List<Command> cmds = new LinkedList<>();
        Iterator<Segment> it = segments.iterator();
        Segment segm;
        do {
        	if (!it.hasNext()) return;
            segm = it.next();
        } while (segm.node.isDeleted());
        w.addNode(segm.node);
        EastNorth a = segm.node.getEastNorth();
        EastNorth ca = a.add(segm.cnext);
        while (it.hasNext()) {
            segm = it.next();
            if (segm.node.isDeleted()) continue;
            EastNorth b = segm.node.getEastNorth();
            EastNorth cb = b.add(segm.cprev);
            if (!a.equalsEpsilon(ca, EPSILON) || !b.equalsEpsilon(cb, EPSILON))
                for (int i = 1; i < detail; i++) {
                    Node n = new Node(Main.getProjection().eastNorth2latlon(cubicBezier(a, ca, cb, b, (double)i / detail)));
                    if (n.getCoor().isOutSideWorld()) {
                        JOptionPane.showMessageDialog(Main.parent,
                                tr("Spline goes outside of the world."));
                        return;
                    }
                    cmds.add(new AddCommand(n));
                    w.addNode(n);
                }
            w.addNode(segm.node);
            a = b;
            ca = a.add(segm.cnext);
        }
        if (!cmds.isEmpty())
            cmds.add(new AddCommand(w));
        Main.main.undoRedo.add(new FinishSplineCommand(cmds));
    }
      
      /**
       * A cubic bezier method to calculate the point at t along the Bezier Curve give
       */

      public static EastNorth cubicBezier(EastNorth a0, EastNorth a1, EastNorth a2, EastNorth a3, double t){
          return new EastNorth(
                  cubicBezierPoint(a0.getX(), a1.getX(), a2.getX(), a3.getX(), t), 
                  cubicBezierPoint(a0.getY(), a1.getY(), a2.getY(), a3.getY(), t)); 
      }

      /**
       * The cubic Bezier equation. 
       */
      private static double cubicBezierPoint(double a0, double a1, double a2, double a3, double t){
          return Math.pow(1-t, 3) * a0 + 3* Math.pow(1-t, 2) * t * a1 + 3*(1-t) * Math.pow(t, 2) * a2 + Math.pow(t, 3) * a3;

      }
      

      public class AddSplineNodeCommand extends AddCommand {
          Segment segm;
          boolean existing;
          public AddSplineNodeCommand(Segment segm, boolean existing) {
              super(segm.node);
              this.segm = segm;
              this.existing = existing;
          }
          @Override
          public boolean executeCommand() {
              segments.addLast(segm);
              if (!existing)
                  return super.executeCommand();
              return true;
          }
          @Override
          public void undoCommand() {
              if (!existing)
                  super.undoCommand();
              segments.removeLast();
          }
          @Override
          public String getDescriptionText() {
        	  if (existing)
                 return tr("Add an existing node to spline");
        	  return super.getDescriptionText();
          }
      }
      public class EditSplineCommand extends Command {
          EastNorth cprev;
          EastNorth cnext;
          Segment segm;
          public EditSplineCommand(Segment segm) {
              this.segm = segm;
              cprev = segm.cprev.add(0, 0);
              cnext = segm.cnext.add(0, 0);
          }
          @Override
          public boolean executeCommand() {
              EastNorth en = segm.cprev;
              segm.cprev = this.cprev;
              this.cprev = en;
              en = segm.cnext;
              segm.cnext = this.cnext;
              this.cnext = en;
              return true;
          }
          @Override
          public void undoCommand() {
              executeCommand();
          }
        @Override
        public void fillModifiedData(Collection<OsmPrimitive> modified,
                Collection<OsmPrimitive> deleted, Collection<OsmPrimitive> added) {
            // This command doesn't touches OSM data
        }
        @Override
        public String getDescriptionText() {
            return "Edit spline";
        }
      }
      public class FinishSplineCommand extends SequenceCommand {
    	  public Segment[] saveSegments;
          public FinishSplineCommand(Collection<Command> sequenz) {
              super(tr("Finish spline"), sequenz);
          }
          @Override
          public boolean executeCommand() {
        	  saveSegments = new Segment[segments.size()];
        	  int i = 0;
        	  for (Segment segm : segments)
        		  saveSegments[i++] = segm;
        	  segments.clear();
        	  return super.executeCommand();
          }
          @Override
          public void undoCommand() {
              super.undoCommand();
              segments.clear();
              segments.addAll(Arrays.asList(saveSegments));
          }
      }
}

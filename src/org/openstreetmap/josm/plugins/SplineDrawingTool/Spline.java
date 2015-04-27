/**
 * 
 */
package org.openstreetmap.josm.plugins.SplineDrawingTool;

//import static org.openstreetmap.josm.plugins.contourmerge.util.Assert.checkArgNotNull;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.NavigatableComponent;

public class Spline {
	public static class Segment {
		public Node point; // Endpoint
		public EastNorth ca, cb; // Control points
	}
	public Node firstPoint;
	public EastNorth firstControl;
	public final List<Segment> segments = new ArrayList<Segment>();
    public void paint(Graphics2D g, MapView mv, Color curveColor, Color ctlColor) {
    	if (firstPoint == null || segments.isEmpty())
    		return;
    	final GeneralPath curv = new GeneralPath();
    	final GeneralPath ctl = new GeneralPath();
    	Point2D pt = mv.getPoint2D(firstPoint);
    	EastNorth en = firstPoint.getEastNorth();
    	curv.moveTo(pt.getX(), pt.getY());
    	ctl.moveTo(pt.getX(), pt.getY());
    	for (Segment segm : segments) {
    		Point2D ca = mv.getPoint2D(en.add(segm.ca));
    		pt = mv.getPoint2D(segm.point);
    		en = segm.point.getEastNorth();
    		Point2D cb = mv.getPoint2D(en.add(segm.cb));

    		curv.curveTo(ca.getX(), ca.getY(), cb.getX(), cb.getY(), pt.getX(), pt.getY());
    		ctl.lineTo(ca.getX(), ca.getY());
    		ctl.moveTo(cb.getX(), cb.getY());
    		ctl.lineTo(pt.getX(), pt.getY());
    	}
    	
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(curveColor);
    	g.draw(curv);
        g.setStroke(new BasicStroke(1));
        g.setColor(ctlColor);
        g.draw(ctl);
	}
	public enum SegmentPoint { ENDPOINT, CONTROL_A, CONTROL_B }; 
    public class PointHandle {
    	public final Integer segmIndex;
    	public final Node prevNode;
    	public final Segment segm;
    	public final SegmentPoint point;
    	public PointHandle(int idx, SegmentPoint point) {
    		if (point == null)
    			throw new IllegalArgumentException("Invalid SegmentPoint passed for PointHandle contructor");
    		if (idx < -2)
    			throw new IllegalArgumentException("Invalid idx passed for PointHandle contructor");
    		if (idx == -1 && point != SegmentPoint.ENDPOINT)
    			throw new IllegalArgumentException("SegmentPoint must be ENDPOINT for first point");
    		segmIndex = idx;
    		this.point = point;
    		if (idx == -1) {
    			prevNode = null;
    			segm = null;
    		} else {
    		    if (idx == 0)
                    prevNode = firstPoint;
    		    else
                    prevNode = segments.get(idx-1).point;
    		    segm = segments.get(idx);
    		}
    	}
    	public EastNorth getPoint() {
    		if (segmIndex == -1)
    			return firstPoint.getEastNorth();
    		switch (point) {
    		case ENDPOINT: return segm.point.getEastNorth(); 
    		case CONTROL_A: return prevNode.getEastNorth().add(segm.ca); 
    		case CONTROL_B: return segm.point.getEastNorth().add(segm.cb); 
    		}
    		throw new AssertionError();
    	}
    	public void movePoint(EastNorth en) {
    		if (segmIndex == -1) {
    			 firstPoint.setEastNorth(en);
    			 return;
    		}
    		switch (point) {
    		case ENDPOINT: segm.point.setEastNorth(en); return;
    		case CONTROL_A: segm.ca = en.sub(prevNode.getEastNorth()); return;
    		case CONTROL_B: segm.cb = en.sub(segm.point.getEastNorth()); return;
    		}
    		throw new AssertionError();
    	}
    }
    public PointHandle getNearestPoint(MapView mv, Point2D point) {
    	PointHandle bestPH = null;
    	double bestDistSq = 3 * NavigatableComponent.PROP_SNAP_DISTANCE.get();
    	double distSq = point.distanceSq(mv.getPoint2D(firstPoint));
		if (distSq < bestDistSq) {
			bestPH = new PointHandle(-1, SegmentPoint.ENDPOINT);
			bestDistSq = distSq;
		}
    	for (int i = 0; i < segments.size(); i++) {
    		for(SegmentPoint sp : SegmentPoint.values()) {
    			PointHandle ph = new PointHandle(i, sp);
    			distSq = point.distanceSq(mv.getPoint2D(ph.getPoint()));
    			if (distSq < bestDistSq) {
    				bestPH = ph;
    				bestDistSq = distSq;
    			}
    		}
    	}
    	return bestPH;
    }
    public void finishSpline(int detail) {
    	if (firstPoint == null) {
    		segments.clear();
    		return;
    	}
    	Way w = new Way();
    	List<Command> cmds = new LinkedList<>();
    	w.addNode(firstPoint);
    	EastNorth a = firstPoint.getEastNorth();
    	for (Segment segm : segments) {
    		EastNorth ca = a.add(segm.ca);
    		EastNorth cb = segm.point.getEastNorth().add(segm.cb);
    		EastNorth b = segm.point.getEastNorth();
    		for (int i = 1; i < detail; i++) {
    			Node n = new Node(Main.getProjection().eastNorth2latlon(cubicBezier(a, ca, cb, b, (double)i / detail)));
    			cmds.add(new AddCommand(n));
    			w.addNode(n);
    		}
    		w.addNode(segm.point);
    		a = b;
    	}
    	if (!cmds.isEmpty()) {
    		cmds.add(new AddCommand(w));
    		Main.main.undoRedo.add(new SequenceCommand("Draw a spline", cmds));
    	}
    	segments.clear();
        firstPoint = null;
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
	   * A quadratic Bezier method to calculate the point at t along the Bezier Curve give
	   * the parameter points.
	   * @param p1
	   * @param p2
	   * @param p3
	   * @param t A value between 0 and 1, inclusive. 
	   * @return
	   */

	  public static Point2D quadBezier(Point2D p1, Point2D p2, Point2D p3, double t){
	  	return new Point2D.Double(
	  			quadBezierPoint(p1.getX(), p2.getX(), p3.getX(), t), 
	  			quadBezierPoint(p1.getY(), p2.getY(), p3.getY(), t) 
	  			//quadBezierPoint(p1.z, p2.z, p3.z, t)
	  			);
	  }

	  /**
	   * The cubic Bezier equation. 
	   */
	  private static double cubicBezierPoint(double a0, double a1, double a2, double a3, double t){
	  	return Math.pow(1-t, 3) * a0 + 3* Math.pow(1-t, 2) * t * a1 + 3*(1-t) * Math.pow(t, 2) * a2 + Math.pow(t, 3) * a3;

	  }

	  /**
	   * The quadratic Bezier equation,
	   */
	  private static double quadBezierPoint(double a0, double a1, double a2, double t){
	  	return Math.pow(1-t, 2) * a0 + 2* (1-t) * t * a1 + Math.pow(t, 2) * a2;
	  }

	  /**
	   * Calculates the center point between the two parameter points.
	   */
	  public static Point2D center(Point2D p1, Point2D p2){
	  	return new Point2D.Double(
	  			(p1.getX() + p2.getX()) / 2, 
	  			(p1.getY() + p2.getY()) / 2
	  			//(p1.z + p2.z) / 2
	  			);
	  }
}

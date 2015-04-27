/**
 * 
 */
package org.openstreetmap.josm.plugins.SplineDrawingTool;

//import static org.openstreetmap.josm.plugins.contourmerge.util.Assert.checkArgNotNull;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
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
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.NavigatableComponent;

public class Spline {
	public static class Segment {
		public Node point; // Endpoint
		public EastNorth cprev, cnext; // Relative offsets of control points
		public Segment(Node point) {
			this.point = point;
			cprev = new EastNorth(0, 0);
			cnext = new EastNorth(0, 0);
		}
	}
	public final LinkedList<Segment> segments = new LinkedList<Segment>();
    public void paint(Graphics2D g, MapView mv, Color curveColor, Color ctlColor, Point helperEndpoint) {
    	if (segments.isEmpty())
    		return;
    	final GeneralPath curv = new GeneralPath();
    	final GeneralPath ctl = new GeneralPath();

    	Point2D cbPrev = null;
    	for (Segment segm : segments) {
        	Point2D pt = mv.getPoint2D(segm.point);
        	EastNorth en = segm.point.getEastNorth();

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
    		EastNorth en = segm.point.getEastNorth();
    		switch (point) {
    		case ENDPOINT: return en; 
    		case CONTROL_PREV: return en.add(segm.cprev); 
    		case CONTROL_NEXT: return en.add(segm.cnext); 
    		}
    		throw new AssertionError();
    	}
    	public void movePoint(EastNorth en) {
    		switch (point) {
    		case ENDPOINT: segm.point.setEastNorth(en); return;
    		case CONTROL_PREV: segm.cprev = segm.point.getEastNorth().sub(en); return;
    		case CONTROL_NEXT: segm.cnext = segm.point.getEastNorth().sub(en); return;
    		}
    		throw new AssertionError();
    	}
    }
    public PointHandle getNearestPoint(MapView mv, Point2D point) {
    	PointHandle bestPH = null;
    	double bestDistSq =  NavigatableComponent.PROP_SNAP_DISTANCE.get();
    	bestDistSq = bestDistSq * bestDistSq; 
    	for (Segment segm : segments) {
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
    public void finishSpline(int detail) {
    	if (segments.isEmpty())
    		return;
    	Way w = new Way();
    	List<Command> cmds = new LinkedList<>();
    	Iterator<Segment> it = segments.iterator();
    	Segment segm = it.next();
    	w.addNode(segm.point);
    	EastNorth a = segm.point.getEastNorth();
		EastNorth ca = a.add(segm.cnext);
    	while (it.hasNext()) {
    		segm = it.next();
    		EastNorth b = segm.point.getEastNorth();
    		EastNorth cb = b.add(segm.cprev);
    		for (int i = 1; i < detail; i++) {
    			Node n = new Node(Main.getProjection().eastNorth2latlon(cubicBezier(a, ca, cb, b, (double)i / detail)));
    			if (n.getCoor().isOutSideWorld()) {
                    JOptionPane.showMessageDialog(Main.parent,
                            tr("Spline goes outside world."));
                    return;
                }
    			cmds.add(new AddCommand(n));
    			w.addNode(n);
    		}
    		w.addNode(segm.point);
    		a = b;
    		ca = a.add(segm.cnext);
    	}
    	if (!cmds.isEmpty()) {
    		cmds.add(new AddCommand(w));
    		Main.main.undoRedo.add(new SequenceCommand("Draw a spline", cmds));
    	}
    	segments.clear();
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

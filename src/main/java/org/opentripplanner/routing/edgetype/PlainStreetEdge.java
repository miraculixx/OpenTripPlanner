/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.routing.edgetype;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.opentripplanner.common.TurnRestriction;
import org.opentripplanner.common.TurnRestrictionType;
import org.opentripplanner.common.geometry.CompactLineString;
import org.opentripplanner.common.geometry.DirectionUtils;
import org.opentripplanner.common.geometry.PackedCoordinateSequence;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.StateEditor;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.util.ElevationProfileSegment;
import org.opentripplanner.routing.util.ElevationUtils;
import org.opentripplanner.routing.util.SlopeCosts;
import org.opentripplanner.routing.vertextype.IntersectionVertex;
import org.opentripplanner.routing.vertextype.StreetVertex;
import org.opentripplanner.util.BitSetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.LineString;

/**
 * This represents a street segment.
 * 
 * @author novalis
 * 
 */
public class PlainStreetEdge extends StreetEdge implements Cloneable {

    private static Logger LOG = LoggerFactory.getLogger(PlainStreetEdge.class); 

    private static final long serialVersionUID = 1L;

    private static final double GREENWAY_SAFETY_FACTOR = 0.1;

    /** If you have more than 8 flags, increase flags to short or int */
    private static final int BACK_FLAG_INDEX = 0;
    private static final int ROUNDABOUT_FLAG_INDEX = 1;
    private static final int HASBOGUSNAME_FLAG_INDEX = 2;
    private static final int NOTHRUTRAFFIC_FLAG_INDEX = 3;
    private static final int STAIRS_FLAG_INDEX = 4;
    private static final int SLOPEOVERRIDE_FLAG_INDEX = 5;

    /** back, roundabout, stairs, ... */
    private byte flags;

    private ElevationProfileSegment elevationProfileSegment;

    private double length;

    /**
     * bicycleSafetyWeight = length * bicycleSafetyFactor. For example, a 100m street with a safety
     * factor of 2.0 will be considered in term of safety cost as the same as a 150m street with a
     * safety factor of 1.0.
     */
    private float bicycleSafetyFactor;

    private int[] compactGeometry;
    
    private String name;

    private String label;
    
    private boolean wheelchairAccessible = true;

    private StreetTraversalPermission permission;

    private int streetClass = CLASS_OTHERPATH;
    
    /**
     * The speed (meters / sec) at which an automobile can traverse
     * this street segment.
     */
    private float carSpeed;

    private List<TurnRestriction> turnRestrictions = Collections.emptyList();

    /** 0 -> 360 degree angle - the angle at the start of the edge geometry */
    public int inAngle;

    /** 0 -> 360 degree angle - the angle at the end of the edge geometry */
    public int outAngle;

    /**
     * No-arg constructor used only for customization -- do not call this unless you know
     * what you are doing
     */
    public PlainStreetEdge() {
        super(null, null);
    }

    public PlainStreetEdge(StreetVertex v1, StreetVertex v2, LineString geometry, 
            String name, double length,
            StreetTraversalPermission permission, boolean back) {
        // use a default car speed of ~25 mph for splitter vertices and the like
        // TODO(flamholz): do something smarter with the car speed here.
        this(v1, v2, geometry, name, length, permission, back, 11.2f);
    }

    public PlainStreetEdge(StreetVertex v1, StreetVertex v2, LineString geometry, 
            String name, double length,
            StreetTraversalPermission permission, boolean back, float carSpeed) {
        super(v1, v2);
        this.setGeometry(geometry);
        this.length = length;
        this.bicycleSafetyFactor = 1.0f;
        this.elevationProfileSegment = ElevationProfileSegment.getFlatProfile();
        this.name = name;
        this.setPermission(permission);
        this.setBack(back);
        this.setCarSpeed(carSpeed);
        if (geometry != null) {
            try {
                for (Coordinate c : geometry.getCoordinates()) {
                    if (Double.isNaN(c.x)) {
                        System.out.println("X DOOM");
                    }
                    if (Double.isNaN(c.y)) {
                        System.out.println("Y DOOM");
                    }
                }
                double angleR = DirectionUtils.getLastAngle(geometry);
                outAngle = ((int) Math.toDegrees(angleR) + 180) % 360;
                angleR = DirectionUtils.getFirstAngle(geometry);
                inAngle = ((int) Math.toDegrees(angleR) + 180) % 360;
            } catch (IllegalArgumentException iae) {
                LOG.error("exception while determining street edge angles. setting to zero. there is probably something wrong with this street segment's geometry.");
                inAngle = 0;
                outAngle = 0;
            }
        }
    }

    @Override
    public boolean canTraverse(RoutingRequest options) {
        if (options.wheelchairAccessible) {
            if (!isWheelchairAccessible()) {
                return false;
            }
            if (elevationProfileSegment.getMaxSlope() > options.maxSlope) {
                return false;
            }
        }
        
        return canTraverse(options.modes);
    }
    
    @Override
    public boolean canTraverse(TraverseModeSet modes) {
        return getPermission().allows(modes);
    }
    
    private boolean canTraverse(RoutingRequest options, TraverseMode mode) {
        if (options.wheelchairAccessible) {
            if (!isWheelchairAccessible()) {
                return false;
            }
            if (elevationProfileSegment.getMaxSlope() > options.maxSlope) {
                return false;
            }
        }
        return getPermission().allows(mode);
    }

    @Override
    public PackedCoordinateSequence getElevationProfile() {
        return elevationProfileSegment.getElevationProfile();
    }

    @Override
    public boolean setElevationProfile(PackedCoordinateSequence elev, boolean computed) {
        if (elev == null || elev.size() < 2) {
            return false;
        }
        if (isSlopeOverride() && !computed) {
            return false;
        }
        boolean slopeLimit = getPermission().allows(StreetTraversalPermission.CAR);
        SlopeCosts costs = ElevationUtils.getSlopeCosts(elev, slopeLimit);
        elevationProfileSegment = new ElevationProfileSegment(costs, elev);
        bicycleSafetyFactor *= costs.lengthMultiplier;
        bicycleSafetyFactor += costs.slopeSafetyCost / length;
        return costs.flattened;
    }

    @Override
    public boolean isElevationFlattened() {
        return elevationProfileSegment.isFlattened();
    }

    @Override
    public double getDistance() {
        return length;
    }

    @Override
    public State traverse(State s0) {
        final RoutingRequest options = s0.getOptions();
        final TraverseMode currMode = s0.getNonTransitMode();
        StateEditor editor = doTraverse(s0, options, s0.getNonTransitMode());
        State state = (editor == null) ? null : editor.makeState();
        /* Kiss and ride support. Mode transitions occur without the explicit loop edges used in park-and-ride. */
        if (options.kissAndRide) {
            if (options.arriveBy) {
                // Branch search to "unparked" CAR mode ASAP after transit has been used.
                // Final WALK check prevents infinite recursion.
                if (s0.isCarParked() && s0.isEverBoarded() && currMode == TraverseMode.WALK) {
                    editor = doTraverse(s0, options, TraverseMode.CAR);
                    if (editor != null) {
                        editor.setCarParked(false); // Also has the effect of switching to CAR
                        State forkState = editor.makeState();
                        if (forkState != null) {
                            forkState.addToExistingResultChain(state);
                            return forkState; // return both parked and unparked states
                        }
                    }
                }
            } else { /* departAfter */
                // Irrevocable transition from driving to walking. "Parking" means being dropped off in this case.
                // Final CAR check needed to prevent infinite recursion.
                if ( ! s0.isCarParked() && ! getPermission().allows(TraverseMode.CAR) && currMode == TraverseMode.CAR) {
                    editor = doTraverse(s0, options, TraverseMode.WALK);
                    if (editor != null) {
                        editor.setCarParked(true); // has the effect of switching to WALK and preventing further car use
                        return editor.makeState(); // return only the "parked" walking state
                    }

                }
            }
        }
        return state;
    }

    /** return a StateEditor rather than a State so that we can make parking/mode switch modifications for kiss-and-ride. */
    private StateEditor doTraverse(State s0, RoutingRequest options, TraverseMode traverseMode) {
        boolean walkingBike = options.walkingBike;
        boolean backWalkingBike = s0.isBackWalkingBike();
        TraverseMode backMode = s0.getBackMode();
        Edge backEdge = s0.getBackEdge();
        if (backEdge != null) {
            // No illegal U-turns.
            // NOTE(flamholz): we check both directions because both edges get a chance to decide
            // if they are the reverse of the other. Also, because it doesn't matter which direction
            // we are searching in - these traversals are always disallowed (they are U-turns in one direction
            // or the other).
            // TODO profiling indicates that this is a hot spot.
            if (this.isReverseOf(backEdge) || backEdge.isReverseOf(this)) {
                return null;
            }
        }

        // Ensure we are actually walking, when walking a bike
        backWalkingBike &= TraverseMode.WALK.equals(backMode);
        walkingBike &= TraverseMode.WALK.equals(traverseMode);

        /* Check whether this street allows the current mode. If not and we are biking, attempt to walk the bike. */
        if (!canTraverse(options, traverseMode)) {
            if (traverseMode == TraverseMode.BICYCLE) {
                return doTraverse(s0, options.bikeWalkingOptions, TraverseMode.WALK);
            }
            return null;
        }

        // Automobiles have variable speeds depending on the edge type
        double speed = calculateSpeed(options, traverseMode);
        
        double time = length / speed;
        double weight;
        // TODO(flamholz): factor out this bike, wheelchair and walking specific logic to somewhere central.
        if (options.wheelchairAccessible) {
            weight = elevationProfileSegment.getSlopeSpeedFactor() * length / speed;
        } else if (traverseMode.equals(TraverseMode.BICYCLE)) {
            time = elevationProfileSegment.getSlopeSpeedFactor() * length / speed;
            switch (options.optimize) {
            case SAFE:
                weight = bicycleSafetyFactor * length / speed;
                break;
            case GREENWAYS:
                weight = bicycleSafetyFactor * length / speed;
                if (bicycleSafetyFactor <= GREENWAY_SAFETY_FACTOR) {
                    // greenways are treated as even safer than they really are
                    weight *= 0.66;
                }
                break;
            case FLAT:
                /* see notes in StreetVertex on speed overhead */
                weight = length / speed + elevationProfileSegment.getSlopeWorkFactor() * length;
                break;
            case QUICK:
                weight = elevationProfileSegment.getSlopeSpeedFactor() * length / speed;
                break;
            case TRIANGLE:
                double quick = elevationProfileSegment.getSlopeSpeedFactor() * length;
                double safety = bicycleSafetyFactor * length;
                // TODO This computation is not coherent with the one for FLAT
                double slope = elevationProfileSegment.getSlopeWorkFactor() * length;
                weight = quick * options.triangleTimeFactor + slope
                        * options.triangleSlopeFactor + safety
                        * options.triangleSafetyFactor;
                weight /= speed;
                break;
            default:
                weight = length / speed;
            }
        } else {
            if (walkingBike) {
                // take slopes into account when walking bikes
                time = elevationProfileSegment.getSlopeSpeedFactor() * length / speed;
            }
            weight = time;
            if (traverseMode.equals(TraverseMode.WALK)) {
                // take slopes into account when walking
                // FIXME: this causes steep stairs to be avoided. see #1297.
                double costs = ElevationUtils.getWalkCostsForSlope(length, elevationProfileSegment.getMaxSlope());
                // as the cost walkspeed is assumed to be for 4.8km/h (= 1.333 m/sec) we need to adjust
                // for the walkspeed set by the user
                double elevationUtilsSpeed = 4.0 / 3.0;
                weight = costs * (elevationUtilsSpeed / speed);
                time = weight; //treat cost as time, as in the current model it actually is the same (this can be checked for maxSlope == 0)
                /*
                // debug code
                if(weight > 100){
                    double timeflat = length / speed;
                    System.out.format("line length: %.1f m, slope: %.3f ---> slope costs: %.1f , weight: %.1f , time (flat):  %.1f %n", length, elevationProfileSegment.getMaxSlope(), costs, weight, timeflat);
                }
                */
            }
        }

        if (isStairs()) {
            weight *= options.stairsReluctance;
        } else {
            // TODO: this is being applied even when biking or driving.
            weight *= options.walkReluctance;
        }

        StateEditor s1 = s0.edit(this);
        s1.setBackMode(traverseMode);
        s1.setBackWalkingBike(walkingBike);

        /* Compute turn cost. */
        PlainStreetEdge backPSE;
        if (backEdge != null && backEdge instanceof PlainStreetEdge) {
            backPSE = (PlainStreetEdge) backEdge;
            RoutingRequest backOptions = backWalkingBike ?
                    s0.getOptions().bikeWalkingOptions : s0.getOptions();
            double backSpeed = backPSE.calculateSpeed(backOptions, backMode);
            final double realTurnCost;  // Units are seconds.

            // Apply turn restrictions
            if (options.arriveBy && !canTurnOnto(backPSE, s0, backMode)) {
                return null;
            } else if (!options.arriveBy && !backPSE.canTurnOnto(this, s0, traverseMode)) {
                return null;
            }

            /*
             * This is a subtle piece of code. Turn costs are evaluated differently during
             * forward and reverse traversal. During forward traversal of an edge, the turn
             * *into* that edge is used, while during reverse traversal, the turn *out of*
             * the edge is used.
             *
             * However, over a set of edges, the turn costs must add up the same (for
             * general correctness and specifically for reverse optimization). This means
             * that during reverse traversal, we must also use the speed for the mode of
             * the backEdge, rather than of the current edge.
             */
            if (options.arriveBy && tov instanceof IntersectionVertex) { // arrive-by search
                IntersectionVertex traversedVertex = ((IntersectionVertex) tov);

                realTurnCost = backOptions.getIntersectionTraversalCostModel().computeTraversalCost(
                        traversedVertex, this, backPSE, backMode, backOptions, (float) speed,
                        (float) backSpeed);
            } else if (!options.arriveBy && fromv instanceof IntersectionVertex) { // depart-after search
                IntersectionVertex traversedVertex = ((IntersectionVertex) fromv);

                realTurnCost = options.getIntersectionTraversalCostModel().computeTraversalCost(
                        traversedVertex, backPSE, this, traverseMode, options, (float) backSpeed,
                        (float) speed);                
            } else {
                // In case this is a temporary edge not connected to an IntersectionVertex
                LOG.debug("Not computing turn cost for edge {}", this);
                realTurnCost = 0; 
            }

            if (!traverseMode.isDriving()) {
                s1.incrementWalkDistance(realTurnCost / 100);  // just a tie-breaker
            }

            long turnTime = (long) Math.ceil(realTurnCost);
            time += turnTime;
            weight += options.turnReluctance * realTurnCost;
        }
        

        if (walkingBike || TraverseMode.BICYCLE.equals(traverseMode)) {
            if (!(backWalkingBike || TraverseMode.BICYCLE.equals(backMode))) {
                s1.incrementTimeInSeconds(options.bikeSwitchTime);
                s1.incrementWeight(options.bikeSwitchCost);
            }
        }

        if (!traverseMode.isDriving()) {
            s1.incrementWalkDistance(length);
        }

        /* On the pre-kiss/pre-park leg, limit both walking and driving, either soft or hard. */
        int roundedTime = (int) Math.ceil(time);
        if (options.kissAndRide || options.parkAndRide) {
            if (options.arriveBy) {
                if (!s0.isCarParked()) s1.incrementPreTransitTime(roundedTime);
            } else {
                if (!s0.isEverBoarded()) s1.incrementPreTransitTime(roundedTime);
            }
            if (s1.isMaxPreTransitTimeExceeded(options)) {
                if (options.softPreTransitLimiting) {
                    weight += calculateOverageWeight(s0.getPreTransitTime(), s1.getPreTransitTime(),
                            options.maxPreTransitTime, options.preTransitPenalty,
                                    options.preTransitOverageRate);
                } else return null;
            }
        }
        
        /* Apply a strategy for avoiding walking too far, either soft (weight increases) or hard limiting (pruning). */
        if (s1.weHaveWalkedTooFar(options)) {

            // if we're using a soft walk-limit
            if( options.softWalkLimiting ){
                // just slap a penalty for the overage onto s1
                weight += calculateOverageWeight(s0.getWalkDistance(), s1.getWalkDistance(),
                        options.getMaxWalkDistance(), options.softWalkPenalty,
                                options.softWalkOverageRate);
            } else {
                // else, it's a hard limit; bail
                LOG.debug("Too much walking. Bailing.");
                return null;
            }
        }

        s1.incrementTimeInSeconds(roundedTime);
        
        s1.incrementWeight(weight);

        return s1;
    }

    private double calculateOverageWeight(double firstValue, double secondValue, double maxValue,
            double softPenalty, double overageRate) {
        // apply penalty if we stepped over the limit on this traversal
        boolean applyPenalty = false;
        double overageValue;

        if(firstValue <= maxValue && secondValue > maxValue){
            applyPenalty = true;
            overageValue = secondValue - maxValue;
        } else {
            overageValue = secondValue - firstValue;
        }

        // apply overage and add penalty if necessary
        return (overageRate * overageValue) + (applyPenalty ? softPenalty : 0.0);
    }

    /**
     * Calculate the average automobile traversal speed of this segment, given
     * the RoutingRequest, and return it in meters per second.
     */
    private double calculateCarSpeed(RoutingRequest options) {
        return getCarSpeed();
    }
    
    /**
     * Calculate the speed appropriately given the RoutingRequest and traverseMode.
     */
    private double calculateSpeed(RoutingRequest options, TraverseMode traverseMode) {
        if (traverseMode == null) {
            return Double.NaN;
        } else if (traverseMode.isDriving()) {
            // NOTE: Automobiles have variable speeds depending on the edge type
            return calculateCarSpeed(options);
        }
        return options.getSpeed(traverseMode);
    }

    @Override
    public double weightLowerBound(RoutingRequest options) {
        return timeLowerBound(options) * options.walkReluctance;
    }

    @Override
    public double timeLowerBound(RoutingRequest options) {
        return this.length / options.getStreetSpeedUpperBound();
    }

    public double getSlopeSpeedEffectiveLength() {
        return elevationProfileSegment.getSlopeSpeedFactor() * length;
    }

    public double getWorkCost() {
        return elevationProfileSegment.getSlopeWorkFactor() * length;
    }

    public void setBicycleSafetyFactor(float bicycleSafetyFactor) {
        this.bicycleSafetyFactor = bicycleSafetyFactor;
    }

    public float getBicycleSafetyFactor() {
        return bicycleSafetyFactor;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
    }

    @Override
    public PackedCoordinateSequence getElevationProfile(double start, double end) {
        return elevationProfileSegment.getElevationProfile(start, end);
    }

    @Override
    public String toString() {
        return "PlainStreetEdge(" + getId() + ", " + name + ", " + fromv + " -> " + tov
                + " length=" + this.getLength() + " carSpeed=" + this.getCarSpeed()
                + " permission=" + this.getPermission() + ")";
    }

    /** Returns true if there are any turn restrictions defined. */
    public boolean hasExplicitTurnRestrictions() {
        return this.turnRestrictions != null && this.turnRestrictions.size() > 0;
    }

    public void addTurnRestriction(TurnRestriction turnRestriction) {
        if (turnRestrictions.isEmpty()) {
            turnRestrictions = new ArrayList<TurnRestriction>();
        }
        turnRestrictions.add(turnRestriction);
    }

    @Override
    public PlainStreetEdge clone() {
        try {
            return (PlainStreetEdge) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }
    
    public boolean canTurnOnto(Edge e, State state, TraverseMode mode) {
        for (TurnRestriction restriction : turnRestrictions) {
            /* FIXME: This is wrong for trips that end in the middle of restriction.to
             */

            // NOTE(flamholz): edge to be traversed decides equivalence. This is important since 
            // it might be a temporary edge that is equivalent to some graph edge.
            if (restriction.type == TurnRestrictionType.ONLY_TURN) {
                if (!e.isEquivalentTo(restriction.to) && restriction.modes.contains(mode) &&
                        restriction.active(state.getTimeSeconds())) {
                    return false;
                }
            } else {
                if (e.isEquivalentTo(restriction.to) && restriction.modes.contains(mode) &&
                        restriction.active(state.getTimeSeconds())) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public ElevationProfileSegment getElevationProfileSegment() {
        return elevationProfileSegment;
    }

    protected boolean detachFrom() {
        if (fromv != null) {
            for (Edge e : fromv.getIncoming()) {
                if (!(e instanceof PlainStreetEdge)) continue;
                PlainStreetEdge pse = (PlainStreetEdge) e;
                ArrayList<TurnRestriction> restrictions = new ArrayList<TurnRestriction>(pse.turnRestrictions);
                for (TurnRestriction restriction : restrictions) {
                    if (restriction.to == this) {
                        pse.turnRestrictions.remove(restriction);
                    }
                }
            }
        }
        return super.detachFrom();
    }

	@Override
	public double getLength() {
		return this.length;
	}

	@Override
	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public LineString getGeometry() {
		return CompactLineString.uncompactLineString(fromv.getLon(), fromv.getLat(), tov.getLon(), tov.getLat(), compactGeometry);
	}

	public void setGeometry(LineString geometry) {
		this.compactGeometry = CompactLineString.compactLineString(fromv.getLon(), fromv.getLat(), tov.getLon(), tov.getLat(), geometry);
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public boolean isWheelchairAccessible() {
		return wheelchairAccessible;
	}

	public void setWheelchairAccessible(boolean wheelchairAccessible) {
		this.wheelchairAccessible = wheelchairAccessible;
	}

	public StreetTraversalPermission getPermission() {
		return permission;
	}

	public void setPermission(StreetTraversalPermission permission) {
		this.permission = permission;
	}

	public int getStreetClass() {
		return streetClass;
	}

	public void setStreetClass(int streetClass) {
		this.streetClass = streetClass;
	}

	/**
	 * Marks that this edge is the reverse of the one defined in the source
	 * data. Does NOT mean fromv/tov are reversed.
	 */
	public boolean isBack() {
	    return BitSetUtils.get(flags, BACK_FLAG_INDEX);
	}

	public void setBack(boolean back) {
            flags = BitSetUtils.set(flags, BACK_FLAG_INDEX, back);
	}

	public boolean isRoundabout() {
            return BitSetUtils.get(flags, ROUNDABOUT_FLAG_INDEX);
	}

	public void setRoundabout(boolean roundabout) {
	    flags = BitSetUtils.set(flags, ROUNDABOUT_FLAG_INDEX, roundabout);
	}

	public boolean hasBogusName() {
	    return BitSetUtils.get(flags, HASBOGUSNAME_FLAG_INDEX);
	}

	public void setHasBogusName(boolean hasBogusName) {
	    flags = BitSetUtils.set(flags, HASBOGUSNAME_FLAG_INDEX, hasBogusName);
	}

	public boolean isNoThruTraffic() {
            return BitSetUtils.get(flags, NOTHRUTRAFFIC_FLAG_INDEX);
	}

	public void setNoThruTraffic(boolean noThruTraffic) {
	    flags = BitSetUtils.set(flags, NOTHRUTRAFFIC_FLAG_INDEX, noThruTraffic);
	}

	/**
	 * This street is a staircase
	 */
	public boolean isStairs() {
            return BitSetUtils.get(flags, STAIRS_FLAG_INDEX);
	}

	public void setStairs(boolean stairs) {
	    flags = BitSetUtils.set(flags, STAIRS_FLAG_INDEX, stairs);
	}

	public float getCarSpeed() {
		return carSpeed;
	}

	public void setCarSpeed(float carSpeed) {
		this.carSpeed = carSpeed;
	}

	private boolean isSlopeOverride() {
	    return BitSetUtils.get(flags, SLOPEOVERRIDE_FLAG_INDEX);
	}

	public void setSlopeOverride(boolean slopeOverride) {
	    flags = BitSetUtils.set(flags, SLOPEOVERRIDE_FLAG_INDEX, slopeOverride);
	}

	@Override
	public List<TurnRestriction> getTurnRestrictions() {
		return this.turnRestrictions;
	}

	@Override
	public int getInAngle() {
		return this.inAngle;
	}

	@Override
	public int getOutAngle() {
		return this.outAngle;
	}

}

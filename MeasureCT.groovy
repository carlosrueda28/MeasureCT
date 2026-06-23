import qupath.lib.objects.PathObjects
import qupath.lib.roi.GeometryTools
import qupath.lib.objects.classes.PathClass
import qupath.lib.common.ColorTools

import org.locationtech.jts.linearref.LengthIndexedLine
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.operation.distance.DistanceOp
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.*
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.geom.util.LinearComponentExtracter
import org.locationtech.jts.linearref.LinearLocation
import org.locationtech.jts.operation.linemerge.LineSequencer
import org.locationtech.jts.operation.linemerge.LineMerger

import javafx.application.Platform


//-----------------------Functions-----------------------------------

/*
 * Generate cortical measurement lines between two boundaries.
 *
 * First attempts nearest-point measurements.
 * Invalid lines are later corrected using ray-casting.
 *
 * Parameters:
 *  from       : source boundary
 *  to         : destination boundary
 *  inside     : geometry constraining valid lines
 *  lineClass  : class assigned to generated detections
 */

def createCortMeasurements(Geometry from, Geometry to,
                            Geometry inside = gr, String lineClass) {
    //List of valid lines
    def lines = []
    def cLines = []
    def prevLine = null
    //List of start coordinate of invalid lines
    def crossCoords = []
    //Total length of main annotation boundary to create one line
    //detection every 250um.
    def totalLength = from.getLength() 
    def lil = new LengthIndexedLine(from)
    
    for (double d = 0; d <= totalLength; d += step) {
        Coordinate c = lil.extractPoint(d)
        point = gf.createPoint(c)
        pair = DistanceOp.nearestPoints(point, to)
        line = gf.createLineString([pair[0], pair[1]] as Coordinate[])
        
        def liLine = new LengthIndexedLine(line)
        endIndex = line.getLength()
        start = 0 + (endIndex*0.01)
        end = endIndex*0.99
        
        lineSegment = liLine.extractLine(start, end)
        
        
        
        //checks validity of line, and if not valid its saved to 
        //make raycast later
        if (! gr.covers(lineSegment)) {
           crossCoords << c
           cLines << line
           //visionCone(prevLine)
        }
        
        //checks if line is less than 5mm, if not is invalid
        else if (line.getLength()/4000 >= 5) { 
        }
        
        else {
           lines << line
           prevLine = line
        }
    }
    
    
    
    //Make raycast 
    
    for (coord in crossCoords) {
       line = rayCast(coord, to, inside)
       if (! line) { 
       }
       else if (line.getLength()/4000 >= 5) {
       }
       else {
           lines << line
       }
    
    //Convert geometries to QuPath detections
    for (l in lines) {
        roi = GeometryTools.geometryToROI(l, plane)
        pathClass = PathClass.fromString(lineClass)
        detection = PathObjects.createDetectionObject(roi)
        detection.setPathClass(PathClass.fromString(lineClass))
        linesROI << detection 
        }
        
    for (l in cLines) {
        roi = GeometryTools.geometryToROI(l, plane)
        detection = PathObjects.createDetectionObject(roi)
        crossLines << detection 
        }
}
}

def visionCone (line){
    Coordinate p0 = line.getCoordinateN(0)
    Coordinate p1 = line.getCoordinateN(1)
    
    double dx = p1.x - p0.x
    double dy = p1.y - p0.y
    
    double norm = Math.sqrt(dx*dx + dy*dy)
    
    dx /= norm
    dy /= norm
    
    double coneLength = 20000
    double halfAngle = Math.toRadians(60)
    
    double cosA = Math.cos(halfAngle)
    double sinA = Math.sin(halfAngle)
    
    double lx = dx*cosA - dy*sinA
    double ly = dx*sinA + dy*cosA
    
    double rx = dx*cosA + dy*sinA
    double ry = -dx*sinA + dy*cosA
    
    Coordinate apex = p0
    
    Coordinate left = new Coordinate(
        apex.x + lx*coneLength,
        apex.y + ly*coneLength
    )
    
    Coordinate right = new Coordinate(
        apex.x + rx*coneLength,
        apex.y + ry*coneLength
    )
    
    Polygon cone = gf.createPolygon([
        apex,
        left,
        right,
        apex
    ] as Coordinate[])
    
    roi = GeometryTools.geometryToROI(cone, plane)
    det = PathObjects.createDetectionObject(roi)
    addObject(det)
}


/**
 * Extract only linear geometries from a geometry collection.
 * Useful because intersections may generate mixed geometry types
 * (polygons, points, lines, geometry collections).
 */
def extractLines(Geometry geom) {
    def lines = LinearComponentExtracter.getLines(geom)

    if (lines.isEmpty())
        return null

    def gf = new GeometryFactory()

    return gf.buildGeometry(lines)
}

/**
 * Cast radial rays from a coordinate until they intersect the target
 * geometry. Only rays fully contained within the "inside" geometry
 * are considered valid.
 *
 * Parameters:
 *  coord    : origin coordinate
 *  target   : geometry to intersect
 *  inside   : geometry constraining valid rays
 *  n        : number of tested ray angles
 *  maxDist  : maximum ray length
 *
 * Returns:
 *  Shortest valid LineString or null if none found.
 */
def rayCast(Coordinate coord, Geometry to, Geometry inside,
                          int n = 180, double maxDist = 1e6) {

    def validRays = []
    
    def newCoord = new Coordinate(point.getX() + maxDist, point.getY())
    
    def coords = [coord, newCoord] as Coordinate[]

    // base ray (horizontal to the right)
    def base = gf.createLineString(coords)

    for (int i = 0; i < n; i++) {

        double angleDeg = (360.0 * i) / n
        double angleRad = Math.toRadians(angleDeg)

        // ---- rotate ray around origin point ----
        def transform = AffineTransformation.rotationInstance(
                angleRad,
                coord.getX(),
                coord.getY()
        )

        def ray = transform.transform(base)

        // ---- intersection with target geometry ----
        to = extractLines(to)
        
        def inter = ray.intersection(to)

        if (inter.isEmpty())
            continue

        // ---- collect candidate hit points ----
        def hitPoints = []

        switch (inter.getGeometryType()) {

            case "Point":
                hitPoints << inter
                break

            case "MultiPoint":
                for (int j = 0; j < inter.getNumGeometries(); j++)
                    hitPoints << inter.getGeometryN(j)
                break

            case "LineString":
            case "MultiLineString":
            case "GeometryCollection":

                for (int j = 0; j < inter.getNumGeometries(); j++) {
                    def g = inter.getGeometryN(j)

                    if (g instanceof Point) {
                        hitPoints << g
                    } else if (g instanceof LineString) {
                        // take first coordinate (like shapely version)
                        def c = g.getCoordinateN(0)
                        hitPoints << gf.createPoint(c)
                    }
                }
                break
        }

        if (hitPoints.isEmpty())
            continue

        // ---- find nearest hit ----
        def pointGeom = gf.createPoint(coord)
        
        def endPt = hitPoints.min { p ->
            DistanceOp.distance(p, pointGeom)
        }

        def clippedRay = gf.createLineString([
            coord,
            endPt.getCoordinate()
        ] as Coordinate[])

        // ---- filters ----
        def liLine = new LengthIndexedLine(clippedRay)
        endIndex = clippedRay.getLength()
        start = 0 + (endIndex*0.001)
        end = endIndex*0.999
        
        lineSegment = liLine.extractLine(start, end)
        
        
        if (! inside.covers(lineSegment))
            continue

        validRays << clippedRay
    }

    if (validRays.isEmpty())
        return null

    // ---- return shortest ----
    return validRays.min { r -> r.getLength() }
}

def updatePathClasses() {
    def project = getProject()
    try {
        projectClasses = project.getPathClasses()
    }catch (Exception e) {
        projectClasses = [PathClass.NULL_CLASS] 
    }
    
    def pathClasses = getQuPath().getAvailablePathClasses()
    
    requestedClasses = [
                        PathClass.getInstance("BackGround", ColorTools.BLACK),
                        PathClass.getInstance("Gray", ColorTools.CYAN), 
                        PathClass.getInstance("White", ColorTools.WHITE),
                        PathClass.getInstance("PtoB", ColorTools.makeRGB(128, 0, 128)),
                        PathClass.getInstance("BtoP", ColorTools.MAGENTA)
                        ]
    
    for (c in requestedClasses) {
        if (pathClasses.contains(c)) {
           //debug //print  "${c} is in requestedClasses"
        }
        else {
           projectClasses << c
           //debug //print "${c} is NOW in requestedClasses"
    }
}

//debug //print projectClasses

Platform.runLater {
    pathClasses.setAll(projectClasses)
}
}
//--------------------------------Main body------------------------------

imageData = getCurrentImageData()
hierarchy = imageData.getHierarchy()
annotations = hierarchy.getAnnotationObjects()

plane = annotations[0].getROI().getImagePlane()

gf = new GeometryFactory()

//Retrieve annotations by class
for (a in annotations) {
   def classification = a.getPathClass().toString()
   if(classification == "Background") {
      bg = a.getROI().getGeometry()
   }
   else if (classification == "White") {
       wh = a.getROI().getGeometry()
       whAnnotation = a
   }
   else if (classification == "Gray") {
       gr = a.getROI().getGeometry()
       grAnnotation = a
   }
}



//Generate cortical interfaces
pial = bg.intersection(gr.getBoundary()) //Pial boundary
bound = gr.intersection(wh.getBoundary()) //gray-white boundary



//Measurement parameters
crossCoords = []
crossLines = []
step = 1000 // in pixels (1000 pixels == 250um)
linesROI = []


validPialSegments = []

p = LineSequencer.sequence(pial)

def merger = new LineMerger()
merger.add(p)
def merged = merger.getMergedLineStrings()
for (m in merged) {
    /* //Debug only
    roi = GeometryTools.geometryToROI(m, plane)

    ann = PathObjects.createAnnotationObject(roi)

    addObject(ann)
    */
    if (m.getLength() < 30000)
        continue

    def endpointCounts = [:]

    for (int i = 0; i < m.getNumGeometries(); i++) {

        LineString line = m.getGeometryN(i)

        def start = line.getCoordinateN(0)
        def end   = line.getCoordinateN(line.getNumPoints()-1)

        [start, end].each { c ->

            def key = "${c.x},${c.y}"

            endpointCounts[key] = (endpointCounts[key] ?: 0) + 1

        }
        }
        
        if (!endpointCounts.containsValue(1)) {

            for (int i = 0; i < m.getNumGeometries(); i++) {
                validPialSegments << m.getGeometryN(i)
            }
        }
        
        
        else {

            lila = new LengthIndexedLine(m)

            m = lila.extractLine(
                20000,
                m.getLength() - 20000
            )

            for (int i = 0; i < m.getNumGeometries(); i++) {
                validPialSegments << m.getGeometryN(i)
            }
            

        }

        /* //Debug only
        roi = GeometryTools.geometryToROI(m, plane)

        ann = PathObjects.createAnnotationObject(roi)

        addObject(ann)
        */

    }


mergedPial = gf.createMultiLineString(validPialSegments as LineString[])

//Create measurements in both directions
createCortMeasurements(mergedPial, bound, "PtoB") 
createCortMeasurements(bound, mergedPial, "BtoP")

//addObjects(linesROI)
grAnnotation.addChildObjects(linesROI)
//grAnnotation.addChildObjects(crossLines) //Debug only
selectDetections()
addShapeMeasurements("LENGTH")
resetSelection()

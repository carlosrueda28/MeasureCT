import qupath.lib.objects.PathObjects
import qupath.lib.roi.GeometryTools
import qupath.lib.objects.classes.PathClass
import org.locationtech.jts.linearref.LengthIndexedLine
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.operation.distance.DistanceOp
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.*
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.geom.util.LinearComponentExtracter

def middleFraction(geometry, double frac = 0.9) {
    if (!(frac > 0 && frac <= 1)) {
        throw new IllegalArgumentException("frac must be between 0 and 1")
    }

    double total = geometry.getLength()
    double trim = (1 - frac) / 2.0

    double start = total * trim
    double end = total * (1 - trim)

    def lil = new LengthIndexedLine(geometry)

    return lil.extractLine(start, end)
}

def extractLines(Geometry geom) {
    def lines = LinearComponentExtracter.getLines(geom)

    if (lines.isEmpty())
        return null

    def gf = new GeometryFactory()

    return gf.buildGeometry(lines)
}

def shortestRayToMultiline(Coordinate point, Geometry y, Geometry a, Geometry x,
                          int n = 180, double maxDist = 1e6, GeometryFactory gf) {

    def validRays = []
    
    def newPoint = new Coordinate(point.getX() + maxDist, point.getY())
    
    def coords = [point, newPoint] as Coordinate[]

    // base ray (horizontal to the right)
    def base = gf.createLineString(coords)

    for (int i = 0; i < n; i++) {

        double angleDeg = (360.0 * i) / n
        double angleRad = Math.toRadians(angleDeg)

        // ---- rotate ray around origin point ----
        def transform = AffineTransformation.rotationInstance(
                angleRad,
                point.getX(),
                point.getY()
        )

        def ray = transform.transform(base)

        // ---- intersection with target geometry ----
        y = extractLines(y)
        
        def inter = ray.intersection(y)

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
        def pointGeom = gf.createPoint(point)
        
        def endPt = hitPoints.min { p ->
            DistanceOp.distance(p, pointGeom)
        }

        def clippedRay = gf.createLineString([
            point,
            endPt.getCoordinate()
        ] as Coordinate[])

        // ---- filters ----
        if (clippedRay.crosses(a))
            continue

        if (a.contains(clippedRay))
            continue

        if (clippedRay.crosses(x))
            continue

        validRays << clippedRay
    }

    if (validRays.isEmpty())
        return null

    // ---- return shortest ----
    return validRays.min { r -> r.getLength() }
}

def imageData = getCurrentImageData()
def hierarchy = imageData.getHierarchy()
def annotations = hierarchy.getAnnotationObjects()
def plane = annotations[0].getROI().getImagePlane()
def gf = new GeometryFactory()

for (a in annotations) {
   def classification = a.getPathClass().toString()
   if(classification == "Background") {
      bg = a.getROI().getGeometry()
   }
   else if (classification == "White") {
       wh = a.getROI().getGeometry()
   }
   else if (classification == "Gray") {
       gr = a.getROI().getGeometry()
       grAnnotation = a
   }
}




pial = bg.intersection(gr.getBoundary())
bound = gr.intersection(wh.getBoundary())

def lil = new LengthIndexedLine(pial)

def totalLength = pial.getLength()
def step = 1000 // in pixels (1000 pixels == 250um)

def lines = []
def crossLines = []
def crossPoints = []
def y_intersect = []
def x_intersect = []
def distances = []
def linesROI = []




for (double d = 0; d <= totalLength; d += step) {
    Coordinate c = lil.extractPoint(d)
    point = gf.createPoint(c)
    pair = DistanceOp.nearestPoints(point, bound)
    line = gf.createLineString([pair[0], pair[1]] as Coordinate[])
    subLine = middleFraction(line)
    if (subLine.crosses(bg) || bg.contains(subLine)) {
       crossLines << subLine
       crossPoints << c
    }
    
    else if (line.getLength()/4000 >= 5) { 
    }
    
    else {
       lines << line
    }

}

for (point in crossPoints) {
   line = shortestRayToMultiline(point, bound, bg, pial, gf)
   if (! line) { 
   }
   else if (line.getLength()/4000 >= 5) {
   }
   else {
       lines << line
   }
}

for (l in lines) {
    roi = GeometryTools.geometryToROI(l, plane)
    detection = PathObjects.createDetectionObject(roi)
    detection.setPathClass(PathClass.fromString("PtoB"))
    linesROI << detection
}


def linesB = []
def totalLengthB = bound.getLength()
def crossPointsB = []
def lilB = new LengthIndexedLine(bound)

for (double d = 0; d <= totalLengthB; d += step) {
    Coordinate c = lilB.extractPoint(d)
    point = gf.createPoint(c)
    pair = DistanceOp.nearestPoints(point, pial)
    line = gf.createLineString([pair[0], pair[1]] as Coordinate[])
    subLine = middleFraction(line)
    if (subLine.crosses(wh) || wh.contains(subLine)) {
       crossLines << subLine
       crossPointsB << c
    }
    
    else if (line.getLength()/4000 >= 5) { 
    }
    
    else {
       linesB << line
    }

}

for (point in crossPointsB) {
   line = shortestRayToMultiline(point, pial, wh, bound, gf)
   if (! line) { 
   }
   else if (line.getLength()/4000 >= 5) {
   }
   else {
       linesB << line
   }
}

for (l in linesB) {
    roi = GeometryTools.geometryToROI(l, plane)
    detection = PathObjects.createDetectionObject(roi)
    detection.setPathClass(PathClass.fromString("BtoP"))
    linesROI << detection
}

//addObjects(linesROI)
grAnnotation.addChildObjects(linesROI)

/*
def roi2 = GeometryTools.geometryToROI(lines, plane)

def annotation = PathObjects.createAnnotationObject(roi2)
addObject(annotation)

print bound


for (a in annotations) {
   print a
}
*/
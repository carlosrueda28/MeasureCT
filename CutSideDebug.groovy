import qupath.lib.roi.GeometryTools
import qupath.lib.roi.RoiTools

import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.linearref.LengthIndexedLine
import org.locationtech.jts.linearref.LocationIndexedLine
import org.locationtech.jts.linearref.LinearLocation
import org.locationtech.jts.operation.linemerge.LineSequencer

imageData = getCurrentImageData()
hierarchy = imageData.getHierarchy()
annotations = hierarchy.getAnnotationObjects()

plane = annotations[0].getROI().getImagePlane()

factory = new GeometryFactory()

for (a in annotations) {
   def classification = a.getPathClass().toString()
   if(classification == "Background") {
      bg = a.getROI().getGeometry()
   }
   else if (classification == "White") {
       wh = a.getROI().getGeometry()
   }
   else if (classification == "Gray") {
       grROI = a.getROI()
       gr = a.getROI().getGeometry()
       grAnnotation = a
   }
}

//Generate cortical interfaces
pial = bg.intersection(gr.getBoundary()) //Pial boundary
pial = LineSequencer.sequence(pial)
bound = gr.intersection(wh.getBoundary()) //gray-white boundary
tri = bg.buffer(1000).intersection(gr.buffer(1000)).intersection(wh.buffer(1000))
/*
roiP = GeometryTools.geometryToROI(pial, plane)
detectionP = PathObjects.createAnnotationObject(roiP)
addObject(detectionP)
/*
roiB = GeometryTools.geometryToROI(bound, plane)
detectionB = PathObjects.createAnnotationObject(roiB)
addObject(detectionB)

roiT = GeometryTools.geometryToROI(tri, plane)
detectionT = PathObjects.createAnnotationObject(roiT)
addObject(detectionT)


def lil = new LengthIndexedLine(pial)
trimmed = lil.extractLine(5000, pial.getLength())
trimmedROI = GeometryTools.geometryToROI(trimmed, plane)
detectiont = PathObjects.createAnnotationObject(trimmedROI)
addObject(detectiont)
*/

deselectAll()
selectObjects { it.getPathClass() == getPathClass("Gray") }
runPlugin('qupath.lib.plugins.objects.SplitAnnotationsPlugin', '{}')

grayAnns = getAnnotationObjects().findAll {
    it.getPathClass() == getPathClass("Gray")
    }


for (a in grayAnns) {
    aGeom = a.getROI().getGeometry()
    p = bg.intersection(aGeom.getBoundary()) //Pial boundary
    if (p == null || p.isEmpty())
        continue
    if (p.getDimension() != 1)
       continue
    p = LineSequencer.sequence(p)

    lila = new LengthIndexedLine(p)
    if (p.getLength() < 40000)
        continue
    aSegment = lila.extractLine(20000, p.getLength() - 20000)
    aROI = GeometryTools.geometryToROI(aSegment, plane)
    aNewAnn = PathObjects.createAnnotationObject(aROI)
    addObject(aNewAnn)
    
}

selectObjects { it.getPathClass() == getPathClass("Gray") }

clearSelectedObjects()
addObject(grAnnotation)

def lil = new LengthIndexedLine(pial)

def lol = new LocationIndexedLine(pial)
/*
for (int i = 0; i < pial.getBoundary().getNumGeometries(); i++) {
   point = pial.getBoundary().getGeometryN(i)
   point = point.buffer(100)
   pROI = GeometryTools.geometryToROI(point, plane)
   pAnn = PathObjects.createAnnotationObject(pROI)
   addObject(pAnn)
}

//liloStart = new LinearLocation(22, 0, 0.0)
//liloEnd = new LinearLocation(105, 0, 0.0)


liloStart = lil.project(pial.getBoundary().getGeometryN(0).getCoordinate())
liloEnd = lil.project(pial.getBoundary().getGeometryN(1).getCoordinate())

test = lil.extractLine(550000, pial.getLength())
testROI = GeometryTools.geometryToROI(test, plane)
detectionTest = PathObjects.createAnnotationObject(testROI)
addObject(detectionTest)
*/

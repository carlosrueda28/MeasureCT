import groovy.transform.Field

import java.awt.*
import java.awt.image.BufferedImage

import javafx.application.Platform
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Slider
import javafx.scene.control.TextField
import javafx.scene.control.TextFormatter
import javafx.scene.control.CheckBox
import javafx.scene.control.TitledPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.layout.Priority
import javafx.scene.layout.RowConstraints
import javafx.stage.Stage
import javafx.stage.Screen
import javafx.util.converter.IntegerStringConverter
import javafx.geometry.Insets
import javafx.geometry.Pos

import org.locationtech.jts.geom.*
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.geom.util.LinearComponentExtracter
import org.locationtech.jts.linearref.LengthIndexedLine
import org.locationtech.jts.linearref.LinearLocation
import org.locationtech.jts.operation.distance.DistanceOp
import org.locationtech.jts.operation.linemerge.LineMerger
import org.locationtech.jts.operation.linemerge.LineSequencer
import org.locationtech.jts.simplify.VWSimplifier

import qupath.lib.common.ColorTools
import qupath.lib.gui.viewer.QuPathViewer
import qupath.lib.gui.viewer.overlays.AbstractOverlay
import qupath.lib.images.ImageData
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent
import qupath.lib.regions.ImagePlane
import qupath.lib.regions.ImageRegion
import qupath.lib.roi.GeometryTools
import qupath.lib.gui.viewer.QuPathViewerListener
import qupath.lib.gui.charts.HistogramDisplay
import qupath.lib.gui.measure.ObservableMeasurementTableData

import qupath.process.gui.commands.ObjectClassifierCommand


@Field
SliceBoundaries sliceBoundaries

@Field
FragmentOverlay fragmentOverlay

@Field GeometryFactory gf = new GeometryFactory()

@Field ImagePlane plane = ImagePlane.getDefaultPlane()

class SliceBoundaries {
    
    Geometry pial
    Geometry bound
    Geometry gray
    final fragmentsProperty = new SimpleObjectProperty<List>()
    java.util.List<List> validPialSegments
    java.util.List<List> fragmentsGUI
    Geometry mergedPial
    boolean showFragments
    
    int lengthTreshold = 28000
    
    Closure overlayUpdater
    
    SliceBoundaries(Geometry bgGeom, Geometry grGeom, Geometry whGeom, int lengthTreshold, ImagePlane plane) {
        refresh(bgGeom, grGeom, whGeom, lengthTreshold, plane)
    }
    
    void updateMergedPial() {
       def gf = new GeometryFactory()
       validPialSegments.clear()
       fragmentsGUI.each {dts ->
       def geo = dts.geo
       for (int i = 0; i < geo.getNumGeometries(); i++) {
          validPialSegments << geo.getGeometryN(i)
       }
       this.mergedPial = gf.createMultiLineString(validPialSegments as LineString[]) 
       }
    }
    
    void refresh(Geometry bgGeom, Geometry grGeom, Geometry whGeom, int lengthTreshold, ImagePlane plane) {
            //Generate cortical interfaces
            
            def gf = new GeometryFactory()
            
            this.pial = bgGeom.intersection(grGeom.getBoundary()) //Pial boundary
            this.bound = grGeom.intersection(whGeom.getBoundary()) //gray-white boundary
            this.gray = grGeom
            this.lengthTreshold = lengthTreshold*4000
            this.validPialSegments = []
            this.fragmentsGUI = []
            
            def p
    
            try {
                p = LineSequencer.sequence(pial)
            }
            catch (org.locationtech.jts.util.AssertionFailedException e) {
            
                Dialogs.showErrorMessage(
                    "Boundary Reconstruction Failed",
                    """The cortical boundaries could not be reconstructed.
            
            This usually happens when the generated annotation boundaries are incomplete or disconnected.
            
            Possible causes:
             • Missing gray or white matter regions
             • Gaps in the segmentation
             • Very small disconnected fragments
             • Invalid annotation geometry
            
            Try improving the tissue classification or adjusting the refinement parameters before trying again."""
                )
            
                return
            }
            
            def merger = new LineMerger()
            merger.add(p)
            def merged = merger.getMergedLineStrings()
            int fragmentIndex = 0
            for (m in merged) {
                /* //Debug only
                def roi = GeometryTools.geometryToROI(m, plane)
            
                def ann = PathObjects.createAnnotationObject(roi)
            
                addObject(ann)
                */
                
                //Ignore small fragments
                if (m.getLength() < this.lengthTreshold)
                    continue
            
                def endpointCounts = [:]
                
                
                
                //Gather information to check if fragment is closed loop
                for (int i = 0; i < m.getNumGeometries(); i++) {
            
                    LineString line = m.getGeometryN(i)
            
                    def start = line.getCoordinateN(0)
                    def end   = line.getCoordinateN(line.getNumPoints()-1)
            
                    [start, end].each { c ->
            
                        def key = "${c.x},${c.y}"
            
                        endpointCounts[key] = (endpointCounts[key] ?: 0) + 1
            
                    }
                    }
                    //If fragment is a closed loop, add as it is
                    if (!endpointCounts.containsValue(1)) {
            
                        def lila = new LengthIndexedLine(m)
                        def dls = new DynamicLengthSlider(lila, fragmentIndex)
                        dls.onChange = {
                            overlayUpdater?.call() 
                        } as Runnable
                        dls.changed = {
                            updateMergedPial()
                        } as Runnable
                        fragmentsGUI << dls
                        fragmentIndex++
                        
                        for (int j = 0; j < m.getNumGeometries(); j++) {
                            validPialSegments << m.getGeometryN(j)
                        }
                    }
                    
                    //if fragment is not a closed loop, trim it
                    else {
            
                        def totalLength = m.getLength()
                        def lila = new LengthIndexedLine(m)
            
                        m = lila.extractLine(
                            20000,
                            totalLength - 20000
                        )
                        
                        
                        def dls = new DynamicLengthSlider(lila, fragmentIndex)
                        dls.onChange = {
                            overlayUpdater?.call() 
                        } as Runnable
                        dls.changed = {
                            updateMergedPial()
                        } as Runnable
                        fragmentsGUI << dls
                        fragmentIndex++
            
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
            
            fragmentsProperty.set(fragmentsGUI)
            this.mergedPial = gf.createMultiLineString(validPialSegments as LineString[])           
            }
}


def tileCreation() {
    updateOverlay(true)
    setImageType('BRIGHTFIELD_H_DAB')
    setColorDeconvolutionStains('{"Name" : "H-DAB default", "Stain 1" : "Hematoxylin", "Values 1" : "0.65111 0.70119 0.29049", "Stain 2" : "DAB", "Values 2" : "0.26917 0.56824 0.77759", "Background" : " 255 255 255"}')
    createFullImageAnnotation(true)
    runPlugin('qupath.imagej.superpixels.SLICSuperpixelsPlugin', '{"sigmaMicrons":5.0,"spacingMicrons":100.0,"maxIterations":10,"regularization":0.25,"adaptRegularization":false,"useDeconvolved":false}')
    selectDetections()
    runPlugin('qupath.lib.algorithms.IntensityFeaturesPlugin', '{"pixelSizeMicrons":2.0,"region":"ROI","tileSizeMicrons":25.0,"colorOD":true,"colorStain1":false,"colorStain2":false,"colorStain3":false,"colorRed":false,"colorGreen":false,"colorBlue":false,"colorHue":false,"colorSaturation":true,"colorBrightness":false,"doMean":true,"doStdDev":true,"doMinMax":true,"doMedian":true,"doHaralick":true,"haralickDistance":1,"haralickBins":32}')
    resetSelection()
}

def annCreator(updateOnly = false, MIN_FRAGMENT_SIZE = 500000, MAX_HOLE_SIZE = 500000, 
                BG_SIMPLIFY = 50, GM_SIMPLIFY = 1000, WM_SIMPLIFY = 1000, BG_BUFFER = 300,
                WM_BUFFER = 300, GM_BUFFER = 600, LENGTH = 5) {
    // ============================================================================
    // PARAMETERS
    // ============================================================================


    
    if (updateOnly == false) {
            
            if (getAnnotationObjects().isEmpty()) {
                Dialogs.showErrorMessage(
                    "Create annotations",
                    "No annotations with valid classified detections were found.\n\nPlease create and/or classify detections before running this command."
                )
                return
                }
            // ============================================================================
            // CONVERT TILE CLASSIFICATIONS TO CLEAN ANNOTATIONS
            // ============================================================================
            
            getAnnotationObjects().each { a ->
                if (! a.hasChildObjects()) {
                    selectObjects(a)
                    clearSelectedObjects()
                }
            }
            
            selectAnnotations()
            
            runPlugin(
                'qupath.lib.plugins.objects.TileClassificationsToAnnotationsPlugin',
                '{"pathClass":"All classes","deleteTiles":true,"clearAnnotations":true,"splitAnnotations":false}'
            )
            
            selectAnnotations()
            
            runPlugin(
                'qupath.lib.plugins.objects.RefineAnnotationsPlugin',
                """{
                    "minFragmentSizeMicrons":${MIN_FRAGMENT_SIZE},
                    "maxHoleSizeMicrons":${MAX_HOLE_SIZE}
                }"""
            )
    }

    
    
    // ============================================================================
    // RETRIEVE IMAGE DATA AND ANNOTATIONS
    // ============================================================================
    
    def imageData   = getCurrentImageData()
    def hierarchy   = imageData.getHierarchy()
    def annotations = hierarchy.getAnnotationObjects()
    
    if (updateOnly == false) {
        if (getAnnotationObjects().isEmpty()) {
            Dialogs.showErrorMessage(
                "Create annotations",
                "No annotations with valid classified detections were found.\n\nPlease create and/or classify detections before running this command."
            )
            return
        }
    } else {
        if (getAnnotationObjects().isEmpty()) {
            Dialogs.showErrorMessage(
            "Create annotations",
            """The required annotations could not be found.
    
            Expected annotation classes:
             • Background
             • Gray
             • White
            
        Please run the annotations creator first or verify that all three classes exist."""
        )
        return
        }
    }
    
    if (getAnnotationObjects().isEmpty()) {
        Dialogs.showErrorMessage(
        "Create annotations",
        """The required annotations could not be found.

        Expected annotation classes:
         • Background
         • Gray
         • White
        
        Please run the classifier first or verify that all three classes exist."""
    )
    return
    }
    
    def plane = annotations[0].getROI().getImagePlane()
    def gf    = new GeometryFactory()
    
    
    // ============================================================================
    // RETRIEVE GEOMETRIES BY CLASS
    // ============================================================================
    
    Geometry backgroundGeom = null
    Geometry whiteGeom      = null
    Geometry grayGeom       = null
    
    annotations.each { annotation ->
    
        switch (annotation.getPathClass()?.toString()) {
    
            case "Background":
                backgroundGeom = annotation.getROI().getGeometry()
                break
    
            case "White":
                whiteGeom = annotation.getROI().getGeometry()
                break
    
            case "Gray":
                grayGeom = annotation.getROI().getGeometry()
                break
        }
    }
    
    if (backgroundGeom == null || grayGeom == null || whiteGeom == null) {
        Dialogs.showErrorMessage(
            "Create annotations",
            "The annotation classes 'Background', 'Gray', and 'White' are required."
        )
        return
    }
    
    
    
    // ============================================================================
    // SIMPLIFY GEOMETRIES
    // ============================================================================
    //
    // Remove small jagged boundaries and noisy contour vertices.
    // This greatly improves the stability of later geometric operations.
    //
    
    def backgroundSmooth = VWSimplifier.simplify(backgroundGeom, BG_SIMPLIFY)
    def whiteSmooth      = VWSimplifier.simplify(whiteGeom, WM_SIMPLIFY)
    def graySmooth       = VWSimplifier.simplify(grayGeom, GM_SIMPLIFY)
    
    
    // ============================================================================
    // MORPHOLOGICAL SMOOTHING
    // ============================================================================
    //
    // Buffer outward and inward to:
    // - smooth rough boundaries
    // - close narrow gaps
    // - remove tiny protrusions
    //
    
    backgroundSmooth = backgroundSmooth.buffer(BG_BUFFER)
    whiteSmooth      = whiteSmooth.buffer(WM_BUFFER)
    graySmooth       = graySmooth.buffer(GM_BUFFER)
    
    
    // Additional simplification after buffering
    backgroundSmooth = VWSimplifier.simplify(backgroundSmooth, 1000)
    whiteSmooth      = VWSimplifier.simplify(whiteSmooth, 1000)
    
    
    // Shrink back to approximately original size
    backgroundSmooth = backgroundSmooth.buffer(-BG_BUFFER)
    whiteSmooth      = whiteSmooth.buffer(-WM_BUFFER)
    
    
    // ============================================================================
    // RECONSTRUCT GRAY MATTER
    // ============================================================================
    //
    // Ensure gray matter occupies only the space between:
    //
    //     Background
    //         ↓
    //      Gray Matter
    //         ↓
    //      White Matter
    //
    // by removing any overlap with the other compartments.
    //
    
    graySmooth = graySmooth.difference(backgroundSmooth)
    graySmooth = graySmooth.difference(whiteSmooth)
    
    
    // ============================================================================
    // REPLACE EXISTING ANNOTATIONS
    // ============================================================================


    sliceBoundaries.refresh(backgroundSmooth, graySmooth, whiteSmooth, LENGTH, plane)

    
    
    clearAnnotations()
    
    
    
    
    // ============================================================================
    // HELPER FUNCTION
    // ============================================================================
    
    def addClassAnnotation = { Geometry geom, String className ->
    
        def roi = GeometryTools.geometryToROI(geom, plane)
    
        def annotation = PathObjects.createAnnotationObject(roi)
    
        annotation.setPathClass(
            PathClass.fromString(className)
        )
    
        addObject(annotation)
        }
    
    
    // ============================================================================
    // CREATE FINAL CLEANED ANNOTATIONS
    // ============================================================================
    
    addClassAnnotation(backgroundSmooth, "Background")
    addClassAnnotation(whiteSmooth,      "White")
    addClassAnnotation(graySmooth,       "Gray") 
}

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
                            Geometry inside, String lineClass, listLines) {
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
    def gf = new GeometryFactory()
    
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
        if (! inside.covers(lineSegment)) {
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
        listLines << detection 
        }
        
    for (l in cLines) {
        roi = GeometryTools.geometryToROI(l, plane)
        detection = PathObjects.createDetectionObject(roi)
        crossLines << detection 
        }
}
}


/**
 * Extract only linear geometries from a geometry collection.
 * Useful because intersections may generate mixed geometry types
 * (polygons, points, lines, geometry collections).
 */
def extractLines(Geometry geom) {
    def gf = new GeometryFactory()
    def lines = LinearComponentExtracter.getLines(geom)

    if (lines.isEmpty())
        return null

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

    def gf = new GeometryFactory()
    
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
    if (project == null)
        return
    
    def projectClasses = new ArrayList<>(project.getPathClasses())
    
    def requestedClasses = [
                        PathClass.getInstance("Background", ColorTools.BLACK),
                        PathClass.getInstance("Gray", ColorTools.CYAN), 
                        PathClass.getInstance("White", ColorTools.WHITE),
                        PathClass.getInstance("PtoB", ColorTools.makeRGB(128, 0, 128)),
                        PathClass.getInstance("BtoP", ColorTools.MAGENTA)
                        ]
    
    requestedClasses.each { c ->
        if (!projectClasses.any { it == c })
            projectClasses.add(c)
    }
    
    project.setPathClasses(projectClasses)
    project.syncChanges()

//debug //print projectClasses
}

def MeasureCT(SliceBoundaries) {
    try {
        def mergedPial = SliceBoundaries.mergedPial
        def bound = SliceBoundaries.bound
        def gray = SliceBoundaries.gray
        
        
        
        //Measurement parameters
        crossCoords = []
        crossLines = []
        step = 1000 // in pixels (1000 pixels == 250um)
        linesROI = []
        
        
        
        //Create measurements in both directions
        createCortMeasurements(mergedPial, bound, gray, "PtoB", linesROI) 
        createCortMeasurements(bound, mergedPial, gray, "BtoP", linesROI)
        
        addObjects(linesROI)
        //grAnnotation.addChildObjects(linesROI)
        //grAnnotation.addChildObjects(crossLines) //Debug only
        selectDetections()
        addShapeMeasurements("LENGTH")
        resetSelection()
    }
    catch (e) {

        Dialogs.showErrorMessage(
            "Cortical Thickness Measurement Failed",
            """Unable to generate cortical thickness measurements.
    
    The cortical boundary is empty or invalid, so measurement lines could not be created.
    
    Possible causes:
     • The pial or white matter boundary was not generated correctly.
     • The boundary contains empty or disconnected geometries.
     • The annotation requires refinement before measurements can be computed.
    
    Please verify the generated annotations and try again."""
        )
    
        return
    }

}

def updateOverlay(closeWindow = false) {

    def viewer = getCurrentViewer()

    if (fragmentOverlay != null) {
        viewer.getCustomOverlayLayers().remove(fragmentOverlay)
    }
    
    if (closeWindow == false) {
        fragmentOverlay = new FragmentOverlay(viewer, sliceBoundaries)
        viewer.getCustomOverlayLayers().add(fragmentOverlay)
        viewer.repaint()
    }    
}

class DynamicLengthSlider {
    Runnable changed
    Runnable onChange
    double totalLength
    double startCutLength
    double endCutLength
    LengthIndexedLine lila
    HBox gui
    Geometry geo
    double pCC
    Slider startSlider
    Slider endSlider
    CheckBox validGeom
    int index
    
   DynamicLengthSlider(LengthIndexedLine lila, int index, double startCutLength = 5, double endCutLength = 5, double pCC = 4000) {
       this.lila = lila
       this.pCC = pCC
       this.index = index
       this.totalLength = lila.getEndIndex()
       if (this.totalLength/this.pCC/2 < startCutLength) {
          this.startCutLength = this.totalLength/this.pCC/4 
       } else {
          this.startCutLength = startCutLength
       }
       if (this.totalLength/this.pCC/2 < endCutLength) {
          this.endCutLength = this.totalLength/this.pCC/4 
       } else {
          this.endCutLength = endCutLength
       }
       
       
       this.geo = lila.extractLine(this.startCutLength * this.pCC, this.totalLength - (this.endCutLength * this.pCC))
       this.guiCreator(index)
   }
   
   void updateGeo() {
      this.geo = this.lila.extractLine(this.startCutLength * this.pCC, this.totalLength - (this.endCutLength * this.pCC)) 
   }
   
   void guiCreator(i) {       
       def segmentHBox = new HBox()
       segmentHBox.getChildren().add(new Label(i.toString()))
       segmentHBox.setSpacing(10)
       

       
       def startHBox = new HBox()
       startHBox.setSpacing(10)
       def startLabel = new Label("Start")
       startLabel.setMinWidth(30)
       startLabel.setPrefWidth(30)
       startHBox.getChildren().add(startLabel)
       this.startSlider = new Slider(0, (this.totalLength/this.pCC/2).round(1), this.startCutLength)
       this.startSlider.setShowTickLabels(true)
       startHBox.getChildren().add(this.startSlider)
       
       this.startSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
           this.startCutLength = newVal
           this.updateGeo()
           if (onChange != null)
               onChange.run()
       })
       
       this.startSlider.valueChangingProperty().addListener((obs, oldValue, changing) -> {
           if(!changing) {
               if(changed != null) {
                  changed.run() 
               }                   
           }
       })
       
       def startValue = new Label(this.startSlider.getValue().toString())
       startValue.textProperty().bind(
           this.startSlider.valueProperty().asString("%.1f")
       )
       startHBox.getChildren().add(startValue)
       startHBox.getChildren().add(new Label(" mm"))

       
       
       
       def endHBox = new HBox()
       endHBox.setSpacing(10)
       def endLabel   = new Label("End")
       endLabel.setMinWidth(30)
       endLabel.setPrefWidth(30)
       endHBox.getChildren().add(endLabel)
       this.endSlider = new Slider(0, (this.totalLength/this.pCC/2).round(1), this.endCutLength)
       this.endSlider.setShowTickLabels(true)
       endHBox.getChildren().add(this.endSlider)
       
       this.endSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
           this.endCutLength = newVal
           this.updateGeo()
           if (onChange != null)
               onChange.run()
       })
       
       this.endSlider.valueChangingProperty().addListener((obs, oldValue, changing) -> {
           if(!changing) {
               if(changed != null) {
                  changed.run() 
               }                   
           }
       })
       
       
       def endValue = new Label(endSlider.getValue().toString())
       endValue.textProperty().bind(
           endSlider.valueProperty().asString("%.1f")
       )
       endHBox.getChildren().add(endValue)
       endHBox.getChildren().add(new Label(" mm"))
       
       def fragmentVBox = new VBox()
       fragmentVBox.getChildren().add(startHBox)
       fragmentVBox.getChildren().add(endHBox)
       segmentHBox.getChildren().add(fragmentVBox)
       
       this.validGeom = new CheckBox("Valid")
       this.validGeom.setSelected(true)
       
       this.validGeom.selectedProperty().addListener { observable, oldValue, newValue ->
           if(newValue) {
               //add function
               this.startSlider.setDisable(false)
               this.endSlider.setDisable(false)
               this.updateGeo()
               if(onChange != null) {
                  onChange.run() 
               }
               if(changed != null) {
                  changed.run() 
               } 
           }else {
               //add function
               this.startSlider.setDisable(true)
               this.endSlider.setDisable(true)
               this.geo = this.lila.extractLine(0, 0)
               if(onChange != null) {
                  onChange.run() 
               }
               if(changed != null) {
                  changed.run() 
               } 
           }
       }
       
       segmentHBox.getChildren().add(this.validGeom)
       
       this.gui = segmentHBox
   }
}

class FragmentOverlay extends AbstractOverlay {

    QuPathViewer viewer
    SliceBoundaries slice

    FragmentOverlay(QuPathViewer viewer, SliceBoundaries slice) {
        super(viewer.getOverlayOptions())
        this.viewer = viewer
        this.slice = slice
    }

    @Override
    void paintOverlay(
            Graphics2D g2d,
            ImageRegion region,
            double downsample,
            ImageData<BufferedImage> imageData,
            boolean paintCompletely
    ) {
        g2d.setStroke(new BasicStroke(400.0f))
        g2d.setColor(new Color(255, 0, 0))
        
        slice.fragmentsGUI.each { dls ->
            def geo = dls.geo
            def roi = GeometryTools.geometryToROI(geo, ImagePlane.getDefaultPlane())
            Shape shape = roi.getShape()
            g2d.draw(shape)

            
        }
    }
}

def createIntegerField = { int defaultValue ->

    def tf = new TextField(defaultValue.toString())

    tf.setTextFormatter(new TextFormatter({ change ->
        change.text ==~ /[0-9]*/ ? change : null
    }))

    return tf
}

def listener = [
    imageDataChanged: { viewer, oldImageData, newImageData ->
        println "Image changed"

        updateOverlay(true)

    },

    selectedObjectChanged: { viewer, pathObject -> },

    visibleRegionChanged: { viewer, shape -> },

    viewerClosed: { viewer -> }

] as QuPathViewerListener

getCurrentViewer().addViewerListener(listener)

updatePathClasses()


def roi = ROIs.createRectangleROI(0, 0, 1, 1, plane)

def geo = roi.getGeometry()

sliceBoundaries = new SliceBoundaries(geo, geo, geo, 7, plane)
sliceBoundaries.overlayUpdater = {
    updateOverlay()
}

Platform.runLater {
    
    Stage stage = new Stage()
    stage.setOnCloseRequest {event ->
        updateOverlay(true)
    }
    
    GridPane settingsGrid = new GridPane()
    settingsGrid.setVgap(20)
    settingsGrid.setHgap(10)
    settingsGrid.setPadding(new Insets(10))
    
    for (int i = 0; i < 6; i++) {
    settingsGrid.getRowConstraints().add(new RowConstraints())
    }
    
    
    Button tilesBtn = new Button("Run")
    tilesBtn.setOnAction {
        Thread.startDaemon {
           try {
              tileCreation() 
           }catch (Exception e) {
              e.printStackTrace() 
           }
        }
    }
    settingsGrid.add(new Label("Create tiles"), 0, 0)
    settingsGrid.add(tilesBtn, 1, 0)
    
    Button classBtn = new Button("Open train Object Classifier")
    classBtn.setOnAction {
        qupath = QuPathGUI.getInstance()
        new ObjectClassifierCommand(qupath).run()
    }
    settingsGrid.add(new Label("Object classifier"), 0, 1)
    settingsGrid.add(classBtn, 1, 1)
    
    GridPane annGrid = new GridPane()
    annGrid.add(new Label("Min fragment size"), 0, 0)
    annGrid.add(new Label(" µm²"), 2, 0)
    annGrid.add(new Label("Max hole size"),0, 1)
    annGrid.add(new Label(" µm²"), 2, 1)
    annGrid.add(new Label("Background simplification"), 0, 2)
    annGrid.add(new Label("Gray matter simplification"), 0, 3)
    annGrid.add(new Label("White matter simplification"), 0, 4)
    annGrid.add(new Label("Background buffer"), 0, 5)
    annGrid.add(new Label("White matter buffer"), 0, 6)
    annGrid.add(new Label("Gray matter buffer"), 0, 7)
    annGrid.add(new Label("Segment Length Treshold"), 0, 8)
    annGrid.add(new Label(" mm"), 2, 8)
    
    minText      = createIntegerField(500000)
    maxText      = createIntegerField(500000)
    backSimpText = createIntegerField(50)
    graySimpText = createIntegerField(1000)
    whtSimpText  = createIntegerField(1000)
    backBuffText = createIntegerField(300)
    whtBuffText  = createIntegerField(300)
    grayBuffText = createIntegerField(600)
    lengthText   = createIntegerField(7)
    
    annGrid.add(minText, 1, 0)
    annGrid.add(maxText, 1, 1)
    annGrid.add(backSimpText, 1, 2)
    annGrid.add(graySimpText, 1, 3)
    annGrid.add(whtSimpText, 1, 4)
    annGrid.add(backBuffText, 1, 5)
    annGrid.add(whtBuffText, 1, 6)
    annGrid.add(grayBuffText, 1, 7)
    annGrid.add(lengthText, 1, 8)
        
    TitledPane advancedPane = new TitledPane()
    advancedPane.setText("Advanced options")
    advancedPane.setContent(annGrid)
    advancedPane.setExpanded(false)   // collapsed by default
    advancedPane.setCollapsible(true)
    
    Button annBtn = new Button("Create annotations")
    annBtn.setOnAction {
        
        int minFragment = minText.text.toInteger()
        int maxHole     = maxText.text.toInteger()
        int bgSimplify  = backSimpText.text.toInteger()
        int gmSimplify  = graySimpText.text.toInteger()
        int wmSimplify  = whtSimpText.text.toInteger()
        int bgBuffer    = backBuffText.text.toInteger()
        int wmBuffer    = whtBuffText.text.toInteger()
        int gmBuffer    = grayBuffText.text.toInteger()
        int length      = lengthText.text.toInteger()
        

        annCreator(
        false,
        minFragment,
        maxHole,
        bgSimplify,
        gmSimplify,
        wmSimplify,
        bgBuffer,
        wmBuffer,
        gmBuffer,
        length
        )
    }
    
    annVBox = new VBox()
    annVBox.getChildren().add(advancedPane)
    annVBox.getChildren().add(annBtn)
    
    settingsGrid.add(new Label("Create annotations"), 0, 2)
    settingsGrid.add(annVBox, 1, 2)
    
    Button updateBtn = new Button("Update annotations")
    updateBtn.setOnAction {
        int minFragment = minText.text.toInteger()
        int maxHole     = maxText.text.toInteger()
        int bgSimplify  = 0//backSimpText.text.toInteger()
        int gmSimplify  = 0//graySimpText.text.toInteger()
        int wmSimplify  = 0//whtSimpText.text.toInteger()
        int bgBuffer    = 100//backBuffText.text.toInteger()
        int wmBuffer    = 0//whtBuffText.text.toInteger()
        int gmBuffer    = grayBuffText.text.toInteger()
        int length      = lengthText.text.toInteger()
        

        annCreator(
        true,
        minFragment,
        maxHole,
        bgSimplify,
        gmSimplify,
        wmSimplify,
        bgBuffer,
        wmBuffer,
        gmBuffer,
        length
        )
    }
    settingsGrid.add(new Label("Update annotations"), 0, 3)
    settingsGrid.add(updateBtn, 1, 3)
    
    Button measureBtn = new Button("Measure cortical thickness")
    measureBtn.setOnAction {
        MeasureCT(sliceBoundaries)
    }
    
    BorderPane measurePane = new BorderPane()
    measurePane.setPadding(new Insets(10))
    
    measurePane.setTop(new Label("Define start and end segments"))
    
    VBox segmentVBox = new VBox()
    segmentVBox.setSpacing(10)
    
    sliceBoundaries.fragmentsProperty.addListener { obs, oldValue, newValue ->
        segmentVBox.getChildren().clear()
        
        for (dls in newValue) {    
           segmentVBox.getChildren().add(dls.gui)   
        }
    updateOverlay()
    }

    
    measScrollPane = new ScrollPane(segmentVBox)
    measScrollPane.setFitToWidth(true)
    
    measurePane.setCenter(measScrollPane)
    
    HBox measureButtons = new HBox(10)
    measureButtons.setAlignment(Pos.CENTER_RIGHT)
    measureButtons.getChildren().add(measureBtn)
    
    measurePane.setBottom(measureButtons)
    

    
    model = new ObservableMeasurementTableData()
    imageData = getCurrentImageData()
    hierarchy = getCurrentHierarchy()
    
    detections = hierarchy.getDetectionObjects().stream()
        .filter(p -> {
            pc = p.getPathClass()
            pc?.toString() in ["PtoB", "BtoP"]
        })
        .toList()
    model.setImageData(imageData, detections)
    histogram = new HistogramDisplay(model, true)
    
    def hierarchyListener = { PathObjectHierarchyEvent event ->
        def detections = hierarchy.getDetectionObjects().stream()
            .filter(p -> {
                pc = p.getPathClass()
                pc?.toString() in ["PtoB", "BtoP"]
            })
            .toList()
        model.setImageData(imageData, detections)
        histogram.refreshHistogram()
    } as qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener
    
    hierarchy.addListener(hierarchyListener)

    histogram.getPane().setPrefHeight(150)
    histogram.showHistogram("Length")
    
    BorderPane histogramPane = new BorderPane()
    histogramPane.setPadding(new Insets(10))
    histogramPane.setCenter(histogram.getPane())
    
    VBox centerBox = new VBox(15)
    
    centerBox.getChildren().addAll(
        settingsGrid,
        measurePane
    )
    
    VBox.setVgrow(measurePane, Priority.ALWAYS)
    VBox.setVgrow(measScrollPane, Priority.ALWAYS)
    
    BorderPane root = new BorderPane()

    root.setCenter(centerBox)
    root.setBottom(histogramPane)
        
    def bounds = Screen.getPrimary().getVisualBounds()
    Scene scene = new Scene(root, 500, bounds.getHeight())
    
    stage.setAlwaysOnTop(true)

    stage.setScene(scene)
    stage.setTitle("Cortical Measurement - by Carlos Rueda (GNA)")
    stage.show()
}
import javafx.application.Platform
import javafx.stage.Stage
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.layout.VBox
import javafx.scene.layout.HBox
import javafx.scene.layout.GridPane
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.Slider
import javafx.scene.control.ScrollPane
import qupath.lib.roi.GeometryTools
import javafx.beans.property.SimpleObjectProperty

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.simplify.VWSimplifier

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

import qupath.lib.objects.PathObjects
import qupath.lib.roi.GeometryTools
import qupath.lib.objects.classes.PathClass
import qupath.lib.common.ColorTools
import qupath.lib.regions.ImagePlane

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
import groovy.transform.Field

import qupath.lib.gui.viewer.QuPathViewer
import qupath.lib.gui.viewer.overlays.AbstractOverlay
import qupath.lib.images.ImageData
import qupath.lib.regions.ImageRegion

import java.awt.*
import java.awt.image.BufferedImage


@Field
SliceBoundaries sliceBoundaries

class SliceBoundaries {
    
    Geometry pial
    Geometry bound
    final fragmentsProperty = new SimpleObjectProperty<List>()
    java.util.List<List> validPialSegments
    java.util.List<List> fragmentsGUI
    Geometry mergedPial
    boolean showFragments
    
    int lengthTreshold = 30000
    
    SliceBoundaries(Geometry bgGeom, Geometry grGeom, Geometry whGeom, ImagePlane plane) {
        refresh(bgGeom, grGeom, whGeom, plane)
    }
    
    void refresh(Geometry bgGeom, Geometry grGeom, Geometry whGeom, ImagePlane plane) {
            //Generate cortical interfaces
            
            def gf = new GeometryFactory()
            
            this.pial = bgGeom.intersection(grGeom.getBoundary()) //Pial boundary
            this.bound = grGeom.intersection(whGeom.getBoundary()) //gray-white boundary
            this.validPialSegments = []
            this.fragmentsGUI = []
    
            def p = LineSequencer.sequence(pial)
            
            def merger = new LineMerger()
            merger.add(p)
            def merged = merger.getMergedLineStrings()
            for (m in merged) {
                /* //Debug only
                def roi = GeometryTools.geometryToROI(m, plane)
            
                def ann = PathObjects.createAnnotationObject(roi)
            
                addObject(ann)
                */
                
                //Ignore small fragments
                if (m.getLength() < lengthTreshold)
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
            
                        def empty = gf.createLineString(new Coordinate[0])
                        fragmentsGUI << [m,[empty, empty]]
                        
                        for (int i = 0; i < m.getNumGeometries(); i++) {
                            validPialSegments << m.getGeometryN(i)
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
                        
                        def startCut = lila.extractLine(0, 20000)
                        def endCut = lila.extractLine(totalLength - 20000, totalLength)
                        
                        fragmentsGUI << [m, [startCut, endCut]]
            
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
    setImageType('BRIGHTFIELD_H_DAB')
    setColorDeconvolutionStains('{"Name" : "H-DAB default", "Stain 1" : "Hematoxylin", "Values 1" : "0.65111 0.70119 0.29049", "Stain 2" : "DAB", "Values 2" : "0.26917 0.56824 0.77759", "Background" : " 255 255 255"}')
    createFullImageAnnotation(true)
    runPlugin('qupath.imagej.superpixels.SLICSuperpixelsPlugin', '{"sigmaMicrons":5.0,"spacingMicrons":100.0,"maxIterations":10,"regularization":0.25,"adaptRegularization":false,"useDeconvolved":false}')
    selectDetections()
    runPlugin('qupath.lib.algorithms.IntensityFeaturesPlugin', '{"pixelSizeMicrons":2.0,"region":"ROI","tileSizeMicrons":25.0,"colorOD":true,"colorStain1":false,"colorStain2":false,"colorStain3":false,"colorRed":false,"colorGreen":false,"colorBlue":false,"colorHue":false,"colorSaturation":true,"colorBrightness":false,"doMean":true,"doStdDev":true,"doMinMax":true,"doMedian":true,"doHaralick":true,"haralickDistance":1,"haralickBins":32}')
    resetSelection()
}

def annCreator(updateOnly = false) { 
    // ============================================================================
    // PARAMETERS
    // ============================================================================
    
    // Remove small fragments & holes after tile-to-annotation conversion
    double MIN_FRAGMENT_SIZE = 500_000
    double MAX_HOLE_SIZE     = 500_000
    
    // Geometry simplification tolerances
    double BG_SIMPLIFY = 500
    double GM_SIMPLIFY = 1000
    double WM_SIMPLIFY = 1000
    
    // Morphological smoothing buffers
    double BG_BUFFER = 300
    double WM_BUFFER = 300
    double GM_BUFFER = 600
    
    if (updateOnly == false) {
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


    sliceBoundaries.refresh(backgroundSmooth, graySmooth, whiteSmooth, plane)

    
    
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
                            Geometry inside = gr, String lineClass, listLines) {
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
        listLines << detection 
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
    if (project == null)
        return
    
    def projectClasses = new ArrayList<>(project.getPathClasses())
    
    def requestedClasses = [
                        PathClass.getInstance("BackGround", ColorTools.BLACK),
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

def MeasureCT() {
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
    
    def bounds = new SliceBoundaries(bg, gr, wh, plane)
        
    //Measurement parameters
    crossCoords = []
    crossLines = []
    step = 1000 // in pixels (1000 pixels == 250um)
    linesROI = []
    
    
    
    //Create measurements in both directions
    createCortMeasurements(bounds.mergedPial, bounds.bound, "PtoB", linesROI) 
    createCortMeasurements(bounds.bound, bounds.mergedPial, "BtoP", linesROI)
    
    addObjects(linesROI)
    //grAnnotation.addChildObjects(linesROI)
    //grAnnotation.addChildObjects(crossLines) //Debug only
    selectDetections()
    addShapeMeasurements("LENGTH")
    resetSelection()
}

class DynamicLengthSlider {
    int totalLength
    int startFragmentLength
    int endFragmentLength
    HBox gui
    
   DynamicLengthSlider(lilTotalLength, lilStartFragmentLength, lilEndFragmentLength, i) {
       
       def totalLength = lilTotalLength/4000
       def startFragmentLength = lilStartFragmentLength/4000
       def endFragmentLength = lilEndFragmentLength/4000
       
       def segmentHBox = new HBox()
       segmentHBox.getChildren().add(new Label(i.toString()))
       
       def startHBox = new HBox()
       startHBox.getChildren().add(new Label("Start"))
       def startSlider = new Slider(0,totalLength/2, startFragmentLength)
       startSlider.setShowTickLabels(true)
       startHBox.getChildren().add(startSlider)
       def startValue = new Label(startSlider.getValue().toString())
       startValue.textProperty().bind(
           startSlider.valueProperty().asString("%.1f")
       )
       startHBox.getChildren().add(startValue)
       

       
       
       
       def endHBox = new HBox()
       endHBox.getChildren().add(new Label("End"))
       def endSlider = new Slider(0, totalLength/2, endFragmentLength)
       endSlider.setShowTickLabels(true)
       endHBox.getChildren().add(endSlider)
       def endValue = new Label(endSlider.getValue().toString())
       endValue.textProperty().bind(
           endSlider.valueProperty().asString("%.1f")
       )
       endHBox.getChildren().add(endValue)
       
       def fragmentVBox = new VBox()
       fragmentVBox.getChildren().add(startHBox)
       fragmentVBox.getChildren().add(endHBox)
       segmentHBox.getChildren().add(fragmentVBox)
       
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

        slice.fragmentsGUI.flatten().each { geo ->
            def roi = GeometryTools.geometryToROI(geo, ImagePlane.getDefaultPlane())
            Shape shape = roi.getShape()
            g2d.draw(shape)

            
        }
    }
}

updatePathClasses()

def plane = ImagePlane.getDefaultPlane()

def roi = ROIs.createRectangleROI(0, 0, 1, 1, plane)

def geo = roi.getGeometry()

sliceBoundaries = new SliceBoundaries(geo, geo, geo, plane)

Platform.runLater {

    Stage stage = new Stage()
    
    GridPane grid = new GridPane()
    
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
    grid.add(new Label("Create tiles"), 0, 0)
    grid.add(tilesBtn, 1, 0)
    
    Button classBtn = new Button("Open train Object Classifier")
    classBtn.setOnAction {
        print "run"
    }
    grid.add(new Label("Object classifier"), 0, 1)
    grid.add(classBtn, 1, 1)
    
    Button annBtn = new Button("Create annotations")
    annBtn.setOnAction {
        annCreator()
    }
    
    GridPane annGrid = new GridPane()
    annGrid.add(new Label("Min fragment size"), 0, 0)
    annGrid.add(new TextField("500000"), 1, 0)
    annGrid.add(new Label("Max hole size"),0, 1)
    annGrid.add(new TextField("500000"), 1, 1)
    annGrid.add(new Label("Background simplification"), 0, 2)
    annGrid.add(new TextField("500000"), 1, 2)
    annGrid.add(new Label("Gray matter simplification"), 0, 3)
    annGrid.add(new TextField("500000"), 1, 3)
    annGrid.add(new Label("White matter simplification"), 0, 4)
    annGrid.add(new TextField("500000"), 1, 4)
    annGrid.add(new Label("Background buffer"), 0, 5)
    annGrid.add(new TextField("500000"), 1, 5)
    annGrid.add(new Label("Gray matter buffer"), 0, 6)
    annGrid.add(new TextField("500000"), 1, 6)
    annGrid.add(annBtn, 0, 7, 2, 1)
    
    grid.add(new Label("Create annotations"), 0, 2)
    grid.add(annGrid, 1, 2)
    
    Button updateBtn = new Button("Update annotations")
    updateBtn.setOnAction {
        annCreator(updateOnly = true)
    }
    grid.add(new Label("Update annotations"), 0, 3)
    grid.add(updateBtn, 1, 3)
    
    Button measureBtn = new Button("Measure cortical thickness")
    measureBtn.setOnAction {
        Thread.startDaemon {
           try {
              MeasureCT() 
           } catch (Exception e) {
              e.printStackTrace() 
           }
        }
    }
    
    GridPane measGrid = new GridPane()
    measGrid.add(new Label("Define start and end segments"), 0, 0)
    
    VBox segmentVBox = new VBox()
    
    sliceBoundaries.fragmentsProperty.addListener { obs, oldValue, newValue ->
        segmentVBox.getChildren().clear()
        
        for (f in newValue) {
       
           def totalLength = f[0].getLength() ?: 0
           def startFragmentLength = f[1][0].getLength() ?: 0
           def endFragmentLength = f[1][1].getLength() ?: 0
           def index = segmentVBox.getChildren().size()
           def dls = new DynamicLengthSlider(totalLength, startFragmentLength, endFragmentLength, index)           
           
           segmentVBox.getChildren().add(dls.gui)
           
           def viewer = getCurrentViewer()

           viewer.getCustomOverlayLayers().removeIf {
               it instanceof FragmentOverlay
           }
            
           viewer.getCustomOverlayLayers().add(
               new FragmentOverlay(viewer, sliceBoundaries)
           )
            
           viewer.repaint()
           
           
    
        }
    }

    
    measScrollPane = new ScrollPane()
    measScrollPane.setContent(segmentVBox)
    
    measGrid.add(measScrollPane, 0, 1, 2, 1)
    
    measGrid.add(measureBtn, 0,2,2,1)
    
    grid.add(new Label("Measure"), 0, 4)
    grid.add(measGrid, 1, 4)
    
    Button showBtn = new Button("Show")
    grid.add(new Label("Show thickness measurements"), 0, 5)
    grid.add(showBtn, 1, 5)

    Scene scene = new Scene(grid, 500, 800)
    
    stage.setAlwaysOnTop(true)

    stage.setScene(scene)
    stage.setTitle("Cortical measurement")
    stage.show()
}
import qupath.lib.roi.GeometryTools

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.simplify.VWSimplifier


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


// ============================================================================
// CONVERT TILE CLASSIFICATIONS TO CLEAN ANNOTATIONS
// ============================================================================

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
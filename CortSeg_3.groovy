            
runPlugin('qupath.lib.plugins.objects.DilateAnnotationPlugin',
          '{"radiusMicrons":300.0,
          "lineCap":"ROUND",
          "removeInterior":false,
          "constrainToParent":true}')
          
clearSelectedObjects()
clearSelectedObjects()

runPlugin('qupath.lib.plugins.objects.DilateAnnotationPlugin', 
           '{"radiusMicrons":-300.0,
           "lineCap":"ROUND",
           "removeInterior":false,
           "constrainToParent":true}')

clearSelectedObjects()
clearSelectedObjects()

runPlugin('qupath.lib.plugins.objects.DilateAnnotationPlugin',
            '{"radiusMicrons":300.0,
            "lineCap":"ROUND",
            "removeInterior":false,
            "constrainToParent":true}')
            
clearSelectedObjects()
clearSelectedObjects()
duplicateSelectedAnnotations()
clearSelectedObjects()
clearSelectedObjects()

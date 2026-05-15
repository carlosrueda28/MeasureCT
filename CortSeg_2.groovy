selectAnnotations();
runPlugin('qupath.lib.plugins.objects.TileClassificationsToAnnotationsPlugin', '{"pathClass":"All classes","deleteTiles":true,"clearAnnotations":true,"splitAnnotations":false}')
selectAnnotations();
runPlugin('qupath.lib.plugins.objects.RefineAnnotationsPlugin', '{"minFragmentSizeMicrons":500000.0,"maxHoleSizeMicrons":500000.0}')

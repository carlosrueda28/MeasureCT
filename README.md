# Cortical Thickness Measurement Script for QuPath

## Overview

This script provides a semi-automatic workflow for measuring brain cortical thickness in histological whole-slide images using QuPath. It uses a GUI to help the process.

The workflow consists of four main steps:

1. Create superpixel tiles.
2. Convert classified tiles into clean anatomical annotations.
3. Manually adjust fragmented pial boundaries.
4. Generate cortical thickness measurements.

The script assumes that the image has been classified into three tissue classes:

* **Background**
* **Gray**
* **White**

---

# Requirements

* QuPath 0.5.1

---

# Workflow

## Step 1 - Run the script
1.1 Download and install QuPath. Detailed instructions on how to do it at: https://qupath.readthedocs.io/en/stable/docs/intro/installation.html
1.2 Download this GtiHub folder and unzip it in your prefered location. 
1.3 Open a QuPath project and open an image. Detailed instructions on how to do it at: https://qupath.readthedocs.io/en/stable/docs/starting/first_steps.html#starting-out
1.4 Open **Script editor** either by pressing **ctrl + [** or going to **Automate > script editor**. Go to **File > Open** and look for the file extracted at step 1.2. Open the file **Main.groovy**
1.5 On the Run Script windows press **Run** button or press **ctrl + R**. 

This step will:
*Open the cortical measurement Script window.
*Add the necessary classes to run the script.

The script assumes that you have at least three annotations with at least one of every class of "Background", "White", "Gray" and two classes named "PtoB" and "BtoP". The next steps guides you on how to do this semiautomatically through this script on the whole image. If the annotations were created and classified manually please skip to Step 5.

## Step 2 — Create Tiles

2.1 Press **Run** under **Create tiles**.

This step will:

* Create full image annotation.
* Create SLIC superpixels as children of the previous annotation.
* Compute intensity features for every tile.
* Prepare the image for object classification.

---

## Step 3 — Train and Run the Object Classifier

In this step we are going to take advantage of QuPath **Train Object Classifier** native tool to easily get approximate Annotations of the Regions of Interest (ROIs). More information of this tool can be found at: https://qupath.readthedocs.io/en/stable/docs/tutorials/superpixels.html#training-a-classifier

3.1 Create an annotation that cover a portion of the background and piamater tissue with your preferred tool. 
Then, on the Class menu at the left on the Annotation tab, right click on *Background* and with the previous annotation selected
click on *Select object by classification*.

3.2 Repeat the previous step for an annotation with the *Gray* classification sampling some gray matter tissue and 
for an annotation with the *White* classification sampling some white matter tissue.

3.3 Go to **Classify > Object Classification > Train Object Classifier** or simply press **Ctrl+Shift+D**.

3.4 At the bottom of Train Object Classifier Window if your computer is strong enoough you can press "Live update" button to see the stimated classification (Background as black, white matter as white and gray matter as cyan), here if you see some tiles missclaassified, a new annotation with the correct class can be draw over the missclassified tiles and the object classifier will try to correct it. The classification at this step doesnt have to be perfect as some details will be corrected on further steps. 

If your computer is not strong enough, at the bottom of the window, you can write on the text prompt a Classifier name and save it, once saved it will render the classification, f you see some tiles missclaassified, a new annotation with the correct class can be draw over the missclassified tiles, however the changes will not appear until the classifier nave is saved again and overwrites the previous.

If you have previously trained a classifer, you can use it here too.

Note: be sure to have enable to see the detections objects and its filling on the overlay by pressing **D** or **F** if necessary. Having the Fill active will allow us to see their classification by its color.

This step will:

*Assign every tile to one of the following classes: Background, Gray or White.

---

## Step 4 — Create Annotations

4.1 Press **Create annotations**.

The script will:

* Convert classified tiles into annotations.
* Remove small fragments and holes.
* Smooth annotation boundaries.
* Reconstruct the gray matter compartment.
* Generate:

  * Background annotation
  * Gray matter annotation
  * White matter annotation
* Delete any other previous annotation.

---
## Step 5 - Improve Annotations

We are going to take advantage that is easier to modify annotations than detections to finally have accurate ROIs. Some common improvements that can be made are:

*Sulcus involutions: when the pial involutionas are very thin, they normally are taken as gray matter, and two different gyrus can be taken as one. Although this can be prevented by creating annotation over pial fragments as backgrounds, they are still not unusuals. To correct this, I suggest to use the **paint with a brush tool** to extend the background annotations over this pial segments. It may be tempted and logical to also correct the Gray annotation, however as the Background annotation will automatically correct the gray annotation whe pressing the **Update Annotations** button.

*Gray-White matter boundary marked edges: depending on the white matter simplification value when creating annotations, the boundary may be over simplified (giving a polygonal rough edges look) or under simplified (giving a teared look). We can use the brush tool to define the white matter boundary to our liking. Once again, for this is acceptable to only modify the white annotation and not the gray annotation, as the white annotation is going to substract to the gray annotation.

*Gray matter not covered by the gray annotation: possibly the only time that the gray annotation has to be modified, is when an area that is gray matter is not covered by the annotation. As always, the brush tool can help us with this. Take in mind that the borders of the annotation doesnt need to be too detailed, as the borders of gray annotation 

When tha annotations have been modified enough, press **Update Annotations** to apply changes. Repeat these steps as necessary until the annotations are as desired.

---
## Step 6 — Inspect Pial Fragments

The script detects disconnected pial fragments.

Each fragment appears in the measurement panel with two sliders:

* **Start**
* **End**

These sliders trim the ends of individual pial fragments.

This is useful for removing:

* Open fragment ends
* Damaged tissue
* Sectioning artifacts
* False pial connections

While the sliders are being moved:

* The overlay updates in real time.

When the mouse button is released:

* The merged pial boundary is rebuilt automatically.

---

## Step 7 — Measure Cortical Thickness

Press **Measure cortical thickness**.

The script will:

* Sample points along the pial surface.
* Compute nearest-point measurements.
* Detect invalid measurements.
* Correct invalid measurements using radial ray casting.
* Create QuPath detections representing cortical thickness.

Measurements are generated in both directions:

* Pial → Gray/White boundary
* Gray/White boundary → Pial

The resulting detections receive the following classes:

* **PtoB** (pial to white boundary)
* **BtoP** (white boundary to pial)

Length measurements are automatically added to every detection.

---



---

# Output

The script creates:

### Annotations

* Background
* Gray
* White

### Detection classes

* PtoB
* BtoP

Each detection contains:

* Length measurement

---

# Notes

* Small disconnected pial fragments are ignored automatically.
* Large open fragments can be manually trimmed before measurement.
* Invalid nearest-neighbor measurements are corrected using a ray-casting algorithm.
* The overlay shown during fragment editing is temporary and is automatically removed when the control window is closed.

---
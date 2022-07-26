package PML_FixedCells_Tools;


import PML_FixedCellsStardistOrion.StarDist2D;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import ij.process.AutoThresholder;
import ij.process.ImageProcessor;
import io.scif.DependencyException;
import java.awt.Color;
import java.awt.Font;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import static java.lang.Double.parseDouble;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.ImageIcon;
import loci.common.services.ServiceException;
import loci.formats.FormatException;
import loci.formats.meta.IMetadata;
import loci.plugins.util.ImageProcessorReader;
import mcib3d.geom.Object3D;
import mcib3d.geom.Objects3DPopulation;
import mcib3d.geom2.BoundingBox;
import mcib3d.geom2.Object3DComputation;
import mcib3d.geom2.Object3DInt;
import mcib3d.geom2.Objects3DIntPopulation;
import mcib3d.geom2.measurements.Measure2Distance;
import mcib3d.geom2.measurements.MeasureDistancesCenter;
import mcib3d.geom2.measurements.MeasureIntensity;
import mcib3d.geom2.measurements.MeasureObject;
import mcib3d.geom2.measurements.MeasureVolume;
import mcib3d.image3d.ImageHandler;
import mcib3d.spatial.analysis.SpatialStatistics;
import mcib3d.spatial.descriptors.F_Function;
import mcib3d.spatial.descriptors.SpatialDescriptor;
import mcib3d.spatial.sampler.SpatialModel;
import mcib3d.spatial.sampler.SpatialRandomHardCore;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

/**
 *
 * @author phm
 */
public class Tools {

    public boolean canceled = false;
    // min max volume in microns^3 for nucleus
    private double minNuc = 100;
    private double maxNuc = 5000;

    // min volume in microns^3 for dots
    private double minPML = 0.01;
    // max volume in microns^3 for dots
    private double maxPML = 10;
    public  double intFactor = 1.5; 
    
    private Object syncObject = new Object();
    private final double stardistPercentileBottom = 0.2;
    private final double stardistPercentileTop = 99.8;
    private final double stardistProbThreshNuc = 0.5;
    private final double stardistOverlayThreshNuc = 0.25;
    private final double stardistProbThreshDot = 0.45;
    private final double stardistOverlayThreshDot = 0.25;
    private File modelsPath = new File(IJ.getDirectory("imagej")+File.separator+"models");
    private String stardistOutput = "Label Image"; 
    public String stardistCellModel = "";
    public String stardistDotModel = "";
    
    public String[] dotsDetections = {"Stardist", "DOG"};
    public String dotsDetection = "";
    double minDotDOG = 2;
    double maxDotDOG = 4;
    
    public String[] channelNames = {"DAPI", "PML"};
    public Calibration cal = new Calibration();
    
    public int zSlicesToIgnore = 0;
    
    private final ImageIcon icon = new ImageIcon(this.getClass().getResource("/Orion_icon.png"));

    private CLIJ2 clij2 = CLIJ2.getInstance();
    
    
     /**
     * check  installed modules
     * @return 
     */
    public boolean checkInstalledModules() {
        // check install
        ClassLoader loader = IJ.getClassLoader();
        try {
            loader.loadClass("net.haesleinhuepf.clij2.CLIJ2");
        } catch (ClassNotFoundException e) {
            IJ.log("CLIJ not installed, please install from update site");
            return false;
        }
        try {
            loader.loadClass("mcib3d.geom.Object3D");
        } catch (ClassNotFoundException e) {
            IJ.log("3D ImageJ Suite not installed, please install from update site");
            return false;
        }
        return true;
    }
    
     /*
    Find starDist models in Fiji models folder
    */
    public String[] findStardistModels() {
        FilenameFilter filter = (dir, name) -> name.endsWith(".zip");
        File[] modelList = modelsPath.listFiles(filter);
        String[] models = new String[modelList.length];
        for (int i = 0; i < modelList.length; i++) {
            models[i] = modelList[i].getName();
        }
        Arrays.sort(models);
        return(models);
    }   
    
    /* Median filter 
     * Using CLIJ2
     * @param ClearCLBuffer
     * @param sizeXY
     * @param sizeZ
     */ 
    public ClearCLBuffer median_filter(ClearCLBuffer  imgCL, double sizeXY, double sizeZ) {
        ClearCLBuffer imgCLMed = clij2.create(imgCL);
        clij2.mean3DBox(imgCL, imgCLMed, sizeXY, sizeXY, sizeZ);
        clij2.release(imgCL);
        return(imgCLMed);
    }
    
    /**
     * Reset labels of the objects
     */
    public void resetLabels(Objects3DIntPopulation pop){
        float label = 1;
        for(Object3DInt obj: pop.getObjects3DInt()) {
            obj.setLabel(label);
            label++;
        }
    }
    
    /**
     * Filter population by size
     */
    public Objects3DIntPopulation sizeFilterPop(Objects3DIntPopulation pop, double volMin, double volMax) {
        Objects3DIntPopulation popF = new Objects3DIntPopulation();
        int index = 1;
        for (Object3DInt object : pop.getObjects3DInt()) {
            double vol = new MeasureVolume(object).getVolumeUnit();
            if ((vol >= volMin) && (vol <= volMax)) {
                object.setLabel(index);
                popF.addObject(object);
                index++;
            }
        }
        popF.setVoxelSizeXY(cal.pixelWidth);
        popF.setVoxelSizeZ(cal.pixelDepth);
        return(popF);
    }
    
    /**
     * Remove object with one Z
     */
    public Objects3DIntPopulation zFilterPop (Objects3DIntPopulation pop) {
        Objects3DIntPopulation popZ = new Objects3DIntPopulation();
        for (Object3DInt obj : pop.getObjects3DInt()) {
            int zmin = obj.getBoundingBox().zmin;
            int zmax = obj.getBoundingBox().zmax;
            if (zmax != zmin)
                popZ.addObject(obj);
        }
        resetLabels(popZ);
        return popZ;
    }
    
     /**
     * return objects population in an ClearBuffer image
     * @param imgCL
     * @return pop objects population
     */

    public Objects3DIntPopulation getPopFromClearBuffer(ClearCLBuffer imgBin, double min, double max) {
        ClearCLBuffer labelsCL = clij2.create(imgBin);
        clij2.connectedComponentsLabelingBox(imgBin, labelsCL);
        clij2.release(imgBin);
        ClearCLBuffer labelsSizeFilter = clij2.create(imgBin);
        // filter size
        clij2.excludeLabelsOutsideSizeRange(labelsCL, labelsSizeFilter, min/(cal.pixelWidth*cal.pixelWidth*cal.pixelDepth),
                max/(cal.pixelWidth*cal.pixelWidth*cal.pixelDepth));
        clij2.release(labelsCL);
        ImagePlus img = clij2.pull(labelsSizeFilter);
        clij2.release(labelsSizeFilter);
        Objects3DIntPopulation pop = new Objects3DIntPopulation(ImageHandler.wrap(img));
        pop.setVoxelSizeXY(cal.pixelWidth);
        pop.setVoxelSizeZ(cal.pixelDepth);
        flush_close(img);
        return(pop);
    }  
    
    
    public String[] dialog(String[] chs) {
        String[] models = findStardistModels();
        GenericDialogPlus gd = new GenericDialogPlus("Parameters");
        gd.setInsets​(0, 100, 0);
        gd.addImage(icon);
        gd.addMessage("Channels", Font.getFont("Monospace"), Color.blue);
        int index = 0;
        for (String chNames : channelNames) {
            gd.addChoice(chNames+" : ", chs, chs[index]);
            index++;
        }
        gd.addMessage("Stack", Font.getFont(Font.MONOSPACED), Color.blue);
        gd.addNumericField("nb Z slices to ignore : ", zSlicesToIgnore);
        gd.addMessage("--- Stardist model ---", Font.getFont(Font.MONOSPACED), Color.blue);
        if (models.length > 0) {
            gd.addChoice("StarDist cell model :",models, models[0]);
            gd.addChoice("StarDist dots model :",models, models[2]);
        }
        else {
            gd.addMessage("No StarDist model found in Fiji !!", Font.getFont("Monospace"), Color.red);
            gd.addFileField("StarDist cell model :", stardistCellModel);
            gd.addFileField("StarDist dots model :", stardistDotModel);
        }
        gd.addMessage("Cells detection", Font.getFont("Monospace"), Color.blue);
        gd.addNumericField("Min nucleus volume : ", minNuc);
        gd.addNumericField("Max nucleus volume : ", maxNuc);
        gd.addMessage("Dots detection", Font.getFont("Monospace"), Color.blue);
        gd.addChoice("Method : "+" : ", dotsDetections, dotsDetections[0]);
        gd.addNumericField("Min DOG : ", minDotDOG);
        gd.addNumericField("Max DOG : ", maxDotDOG);
        gd.addMessage("PML size filter", Font.getFont("Monospace"), Color.blue);
        gd.addNumericField("Min PML volume : ", minPML);
        gd.addNumericField("Max PML volume : ", maxPML);
        gd.addNumericField("Threshold above diffuse PML intensity : ", intFactor, 2);
        gd.addMessage("Image calibration", Font.getFont("Monospace"), Color.blue);
        gd.addNumericField("XY Pixel size : ", cal.pixelWidth);
        gd.addNumericField("Z Pixel size  : ", cal.pixelDepth);
        gd.showDialog();
        if (gd.wasCanceled())
            canceled = true;
        String[] chChoices = new String[channelNames.length];
        for (int n = 0; n < chChoices.length; n++) 
            chChoices[n] = gd.getNextChoice();
        zSlicesToIgnore = (int) gd.getNextNumber();
        if (models.length > 0) {
            stardistCellModel = modelsPath+File.separator+gd.getNextChoice();
            stardistDotModel = modelsPath+File.separator+gd.getNextChoice();
        }
        else {
            stardistCellModel = gd.getNextString();
            stardistDotModel = gd.getNextString();
        }
        if (stardistCellModel.isEmpty() || stardistDotModel.isEmpty()) {
            IJ.error("No model specify !!");
            return(null);
        }
        minNuc = gd.getNextNumber();
        maxNuc = gd.getNextNumber();
        dotsDetection = gd.getNextChoice();
        minDotDOG = gd.getNextNumber();
        maxDotDOG = gd.getNextNumber();
        minPML = gd.getNextNumber();
        maxPML = gd.getNextNumber();
        intFactor = gd.getNextNumber();
        cal.pixelWidth = cal.pixelHeight = gd.getNextNumber();
        cal.pixelDepth = gd.getNextNumber();
        return(chChoices);
    }
     
    // Flush and close images
    public void flush_close(ImagePlus img) {
        img.flush();
        img.close();
    }

    
/**
     * Find images in folder
     * @param imagesFolder
     * @param imageExt
     * @return 
     */
    public ArrayList<String> findImages(String imagesFolder, String imageExt) {
        File inDir = new File(imagesFolder);
        String[] files = inDir.list();
        if (files == null) {
            System.out.println("No Image found in "+imagesFolder);
            return null;
        }
        ArrayList<String> images = new ArrayList();
        for (String f : files) {
            // Find images with extension
            String fileExt = FilenameUtils.getExtension(f);
            if (fileExt.equals(imageExt))
                images.add(imagesFolder + File.separator + f);
        }
        Collections.sort(images);
        return(images);
    }
    
     /**
     * Find image type
     */
    public String findImageType(File imagesFolder) {
        String ext = "";
        String[] files = imagesFolder.list();
        for (String name : files) {
            String fileExt = FilenameUtils.getExtension(name);
            switch (fileExt) {
               case "nd" :
                   ext = fileExt;
                   break;
                case "czi" :
                   ext = fileExt;
                   break;
                case "lif"  :
                    ext = fileExt;
                    break;
                case "isc2" :
                    ext = fileExt;
                    break;
                case "tif" :
                    ext = fileExt;
                    break;    
            }
        }
        return(ext);
    }
    
     /**
     * Find image calibration
     * @param meta
     * @return 
     */
    public Calibration findImageCalib(IMetadata meta) {
        // read image calibration
        cal.pixelWidth = meta.getPixelsPhysicalSizeX(0).value().doubleValue();
        cal.pixelHeight = cal.pixelWidth;
        if (meta.getPixelsPhysicalSizeZ(0) != null)
            cal.pixelDepth = meta.getPixelsPhysicalSizeZ(0).value().doubleValue();
        else
            cal.pixelDepth = 1;
        cal.setUnit("microns");
        return(cal);
    }
    
    
     /**
     * Find channels name
     * @param imageName
     * @return 
     * @throws loci.common.services.DependencyException
     * @throws loci.common.services.ServiceException
     * @throws loci.formats.FormatException
     * @throws java.io.IOException
     */
    public String[] findChannels (String imageName, IMetadata meta, ImageProcessorReader reader) throws DependencyException, ServiceException, FormatException, IOException {
        int chs = reader.getSizeC();
        String[] channels = new String[chs];
        String imageExt =  FilenameUtils.getExtension(imageName);
        switch (imageExt) {
            case "nd" :
                for (int n = 0; n < chs; n++) 
                {
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelName(0, n).toString();
                }
                break;
            case "lif" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null || meta.getChannelName(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelName(0, n).toString();
                break;
            case "czi" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelFluor(0, n).toString();
                break;
            case "ics" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelExcitationWavelength(0, n).value().toString();
                break;    
            default :
                for (int n = 0; n < chs; n++)
                    channels[n] = Integer.toString(n);
        }
        return(channels);         
    }
    
     
    /** Look for all nuclei
    Do z slice by slice stardist 
    * return nuclei population
    */
   public Objects3DIntPopulation stardistNucleiPop(ImagePlus imgNuc, String stardistModel) throws IOException{
       ImagePlus img = null;
       // resize to be in a stardist-friendly scale
       int width = imgNuc.getWidth();
       int height = imgNuc.getHeight();
       float factor = 0.25f;
       boolean resized = false;
       if (imgNuc.getWidth() > 512) {
           img = imgNuc.resize((int)(width*factor), (int)(height*factor), 1, "none");
           resized = true;
       }
       else
           img = new Duplicator().run(imgNuc);
       
       IJ.run(img, "Remove Outliers", "block_radius_x=10 block_radius_y=10 standard_deviations=1 stack");
       ClearCLBuffer imgCL = clij2.push(img);
       ClearCLBuffer imgCLM = clij2.create(imgCL);
       imgCLM = median_filter(imgCL, 2, 2);
       clij2.release(imgCL);
       ImagePlus imgM = clij2.pull(imgCLM);
       clij2.release(imgCLM);
       flush_close(img);

       // Go StarDist
       File starDistModelFile = new File(stardistModel);
       StarDist2D star = new StarDist2D(syncObject, starDistModelFile);
       star.loadInput(imgM);
       star.setParams(stardistPercentileBottom, stardistPercentileTop, stardistProbThreshNuc, stardistOverlayThreshNuc, stardistOutput);
       star.run();
       flush_close(imgM);
       // label in 3D
       ImagePlus nuclei = (resized) ? star.associateLabels().resize(width, height, 1, "none") : star.associateLabels();
       nuclei.setCalibration(cal);
       Objects3DIntPopulation nPop = sizeFilterPop(new Objects3DIntPopulation(ImageHandler.wrap(nuclei)), minNuc, maxNuc);
       flush_close(nuclei);
       return(nPop);
   }
   
    /** Find PML in nucleus cropped image
    * return pml population
    */
    public Objects3DIntPopulation stardistDotsPopInCells(ImagePlus img) throws IOException{
        File starDistModelFile = new File(stardistDotModel);
        StarDist2D star = new StarDist2D(syncObject, starDistModelFile);
        star.setParams(stardistPercentileBottom, stardistPercentileTop, stardistProbThreshDot, stardistOverlayThreshDot, stardistOutput);
            
        // Go StarDist
        ClearCLBuffer imgCL = clij2.push(img);
        ClearCLBuffer imgCLM = clij2.create(imgCL);
        imgCLM = median_filter(imgCL, 2, 2);
        clij2.release(imgCL);
        ImagePlus imgM = clij2.pull(imgCLM);
        clij2.release(imgCLM);
        star.loadInput(imgM);
        star.run();
        flush_close(imgM);
            
        // label in 3D
        ImagePlus imgPML = star.associateLabels();
        imgPML.setCalibration(cal);
        Objects3DIntPopulation dotsPop = sizeFilterPop(new Objects3DIntPopulation(ImageHandler.wrap(imgPML)), minPML, maxPML);
        flush_close(imgPML);
        return(dotsPop);
    }
   
   
   /**
     * Threshold 
     * USING CLIJ2
     * @param imgCL
     * @param thMed
     * @param fill 
     */
    public ClearCLBuffer threshold(ClearCLBuffer imgCL, String thMed) {
        ClearCLBuffer imgCLBin = clij2.create(imgCL);
        clij2.automaticThreshold(imgCL, imgCLBin, thMed);
        return(imgCLBin);
    }
   
   /**
     * Difference of Gaussians 
     * Using CLIJ2
     * @param imgCL
     * @param size1
     * @param size2
     * @return imgGauss
     */ 
    private ClearCLBuffer DOG(ClearCLBuffer imgCL, double size1, double size2) {
        ClearCLBuffer imgCLDOG = clij2.create(imgCL);
        clij2.differenceOfGaussian3D(imgCL, imgCLDOG, size1, size1, size1, size2, size2, size2);
        return(imgCLDOG);
    } 
        
 /**
     * Find dots population
     * @param imgPML
     * @return 
     */
     public Objects3DIntPopulation findDots(ImagePlus img) {
        IJ.showStatus("Finding dots ....");
        ClearCLBuffer imgCL = clij2.push(img);
        ClearCLBuffer imgDOG = DOG(imgCL, minDotDOG, maxDotDOG);
        clij2.release(imgCL);
        ClearCLBuffer imgCLBin = threshold(imgDOG, "Moments");
        clij2.release(imgDOG);
        Objects3DIntPopulation pop = new Objects3DIntPopulation();
        pop = getPopFromClearBuffer(imgCLBin, minPML, maxPML);
        clij2.release(imgCLBin);
        return(pop);
     }
     
     
     /**
     * Read diffus PML intensity
     * fill PML voxel with zero in PML channel
     * Add to nucObj comment mean intensity pre-processing(integrated = false)
     * or integrated intensity (integrated = true)
     * 
     * @param pmlPop
     * @param nucObj
     * @param imgPML
     * @param integrated
     */
    public  void pmlDiffus(Objects3DIntPopulation pmlPop, Object3DInt nucObj, ImagePlus imgPML, boolean integrated) {
        ImageHandler imhDotsDiffuse = ImageHandler.wrap(imgPML.duplicate());
        double pmlIntDiffuse ;
        double volPMLDilated = 0;
        float dilate = 1.5f;
        for (Object3DInt pmlObj : pmlPop.getObjects3DInt()) {
            // dilate 
            Object3DInt pmlDilatedObj = new Object3DComputation(pmlObj).getObjectDilated(dilate, dilate, dilate);
            pmlDilatedObj.drawObject(imhDotsDiffuse, 0);
            volPMLDilated += new MeasureObject(pmlDilatedObj).measure(MeasureVolume.VOLUME_UNIT);
        }
        double nucVolume = new MeasureObject(nucObj).measure(MeasureVolume.VOLUME_UNIT);
        if (integrated)
            pmlIntDiffuse = new MeasureObject(nucObj).measureIntensity(MeasureIntensity.INTENSITY_SUM,imhDotsDiffuse); 
        else {
            pmlIntDiffuse = new MeasureObject(nucObj).measureIntensity(MeasureIntensity.INTENSITY_AVG,imhDotsDiffuse);
        }
        // put in comment nucleus object diffus intensity 
        nucObj.setComment(Double.toString(pmlIntDiffuse));
        imhDotsDiffuse.closeImagePlus();

    }
    
    /**
     * Intensity filter objects
     * 
     * @param nucObj
     * @param pmlPop
     * @param imgDotsOrg
     * @param imageType
    */
    public Objects3DIntPopulation ObjectsIntFilter(Object3DInt nucObj, Objects3DIntPopulation pmlPop, ImagePlus imgDotsOrg) {
        ImageHandler imhDotsOrg = ImageHandler.wrap(imgDotsOrg.duplicate());
        double pmlDiffuse = parseDouble(nucObj.getComment());
        Objects3DIntPopulation plmPopFilter = new Objects3DIntPopulation();
        // Remove pml with intensity < pml diffuse
        int index = 0;
        for (Object3DInt pmlObj : pmlPop.getObjects3DInt()) {
            double pmlInt = new MeasureIntensity(pmlObj, imhDotsOrg).getValueMeasurement(MeasureIntensity.INTENSITY_AVG);
            //System.out.println("nucleus= "+pmlDiffuse+" intFactor = "+intFactor+" pml = "+pmlInt);
            if (pmlInt > pmlDiffuse*intFactor) {
                pmlObj.setLabel(index);
                plmPopFilter.addObject(pmlObj);
                index++;
            }
        }
        flush_close(imhDotsOrg.getImagePlus());
        return(plmPopFilter);
    }
    
    
     /**
     * Label object
     * @param popObj
     * @param img 
     */
    public void labelsObject (Object3DInt obj, ImageHandler imh) {
        int fontSize = Math.round(8f/(float)imh.getCalibration().pixelWidth);
        Font tagFont = new Font("SansSerif", Font.PLAIN, fontSize);
        float label = obj.getLabel();
        BoundingBox box = obj.getBoundingBox();
        int z = box.zmin;
        int x = box.xmin - 2;
        int y = box.ymin - 2;
        imh.getImagePlus().setSlice(z+1);
        ImageProcessor ip = imh.getImagePlus().getProcessor();
        ip.setFont(tagFont);
        ip.setColor(255);
        ip.drawString(Integer.toString((int)label), x, y);
        imh.getImagePlus().updateAndDraw();
    }
     
    
    /**
     * Save nucleus populations
     */
    public void saveNucleus(Objects3DIntPopulation nucPop, ImagePlus img, String output) {
        ImageHandler imh = ImageHandler.wrap(img).createSameDimensions();
        nucPop.drawInImage(imh);
        IJ.run(imh.getImagePlus(), "glasbey on dark", "");
        // save image for nucleus population
        imh.getImagePlus().setCalibration(cal);
        FileSaver ImgNucFile = new FileSaver(imh.getImagePlus());
        ImgNucFile.saveAsTiff(output);
        imh.closeImagePlus();
    }
    
      /*
    Draw countours of objects
    */
    public void drawContours(Object3DInt obj, ImagePlus img, Color col) {
        ImagePlus imgMask = IJ.createImage("mask", img.getWidth(), img.getHeight(), img.getNSlices(), 8);
        BoundingBox box = obj.getBoundingBox();
        obj.drawObject(ImageHandler.wrap(imgMask), 255);
        for (int z = box.zmin; z < box.zmax; z++) {
            imgMask.setSlice(z+1);
            imgMask.getProcessor().setAutoThreshold(AutoThresholder.Method.Default, true);
            IJ.run(imgMask, "Create Selection", "");
            Roi roi = imgMask.getRoi();
            img.setSlice(z+1);
            img.getProcessor().setColor(col);
            roi.drawPixels(img.getProcessor());
        }   
        flush_close(imgMask);
    }
    
    /**
     * Create diffuse PML image
     * Fill zero in pml dots
     * @param pmlPop
     * @param nucObj
     * @param imgDotsOrg
     * @param outDirResults
     * @param rootName
     * @param index
     * @param seriesName
     */
    public  void saveDiffusImage(Objects3DIntPopulation pmlPop, Object3DInt nucObj, ImagePlus imgDotsOrg, String outDirResults,
            String rootName) {
        ImageHandler imhDotsDiffuse = ImageHandler.wrap(imgDotsOrg.duplicate());
        float dilate = 1.5f;
        for (Object3DInt pmlObj : pmlPop.getObjects3DInt()) {
            // dilate 
            Object3DInt pmlDilatedObj = new Object3DComputation(pmlObj).getObjectDilated(dilate, dilate, dilate);
            pmlDilatedObj.drawObject(imhDotsDiffuse, 0);
        }
        // draw nucleus contours
        ImagePlus imgColor = imhDotsDiffuse.getImagePlus();
        IJ.run(imgColor, "RGB Color", "");
        drawContours(nucObj, imhDotsDiffuse.getImagePlus(), Color.blue);
        // Save diffus
        FileSaver imgDiffus = new FileSaver(imgColor);
        imgDiffus.saveAsTiff(outDirResults + rootName + "-Nuc"+nucObj.getLabel()+"_PMLDiffus.tif");
        flush_close(imgColor);
        imhDotsDiffuse.closeImagePlus();
    }
    
    
    
     
    /**
    * For each nucleus compute F function
     * @param pop
     * @param mask
     * @param nuc
     * @param imgName
     * @param outDirResults
     * @return F SDI
    **/ 
    public  double processF (Objects3DIntPopulation pop, Object3DInt mask, ImagePlus imgPML, String imgName, String outDirResults) {
        
        // Convert Object3DInt & Objects3DIntPopulation in old version
        ImageHandler imhNuc = ImageHandler.wrap(imgPML).createSameDimensions();
        mask.drawObject(imhNuc, 1);
        Object3D nucMask = new Objects3DPopulation(imhNuc).getObject(0);
        ImageHandler imhPml = ImageHandler.wrap(imgPML).createSameDimensions();
        pop.drawInImage(imhPml);
        Objects3DPopulation pmlPop = new Objects3DPopulation(imhPml);
        // define spatial descriptor, model
        SpatialDescriptor spatialDesc = new F_Function(pmlPop.getNbObjects(), nucMask);        
        SpatialModel spatialModel = new SpatialRandomHardCore(pmlPop.getNbObjects(), 0.8, nucMask);
        SpatialStatistics spatialStatistics = new SpatialStatistics(spatialDesc, spatialModel, pop.getNbObjects() + 5, pmlPop);
        spatialStatistics.setEnvelope(0.25);
        spatialStatistics.setVerbose(false);
        System.out.println("Nucleus" + mask.getLabel() + " Sdi = " + spatialStatistics.getSdi());
        return(spatialStatistics.getSdi());
    }
    
    /**
    * Compute nucleus and pml results
    * @param nucObj nucleus
    * @param pmlPop pml population
    * @param imgPML read pml intensity
    * @param imgName image file
     * @param results buffer
    **/
    public  void computeNucParameters(Object3DInt nucObj, Objects3DIntPopulation pmlPop, ImagePlus imgPML, String imgName, BufferedWriter results) throws IOException {
        IJ.showStatus("Computing nucleus parameters ....");
        ImageHandler imhPML = ImageHandler.wrap(imgPML);
        // measure nucleus volume
        // measure pml integrated intensity and volume
        double  nucVolume = new MeasureVolume(nucObj).getVolumeUnit();
        for (Object3DInt pmlObj : pmlPop.getObjects3DInt()) {
            double pmlIntensity = new MeasureIntensity(pmlObj, imhPML).getValueMeasurement(MeasureIntensity.INTENSITY_SUM);
            double pmlVolume = new MeasureVolume(pmlObj).getVolumeUnit();
            Measure2Distance dist = new Measure2Distance(nucObj, pmlObj);
            double minDistCenter = dist.getValue(Measure2Distance.DIST_CC_UNIT);
            results.write(imgName+"\t"+nucObj.getLabel()+"\t"+nucVolume+"\t"+pmlObj.getLabel()+"\t"+pmlIntensity+"\t"+pmlVolume+"\t"+minDistCenter+"\n");
            results.flush();
        }
    }
    
    
    /**
    * Compute global nucleus and pml parameters for fixed cells
    * @param nucObj nucleus
     * @param nucIndex
    * @param pmlPop pml population
    * @param imgPML read pml intensity
    * @param imgName image file
    * @param outDirResults results file
     * @param results buffer
     * @throws java.io.IOException
    **/
    public  void computeNucParameters2(Object3DInt nucObj, Objects3DIntPopulation pmlPop, ImagePlus imgPML, 
            String imgName, String outDirResults, BufferedWriter results) throws IOException {
        IJ.showStatus("Computing nucleus parameters ....");
        ImageHandler imhPML = ImageHandler.wrap(imgPML);
        // measure nucleus volume and integrated intensity in PML diffuse
        // measure pml integrated intensity and volume
        DescriptiveStatistics pmlIntensity = new DescriptiveStatistics();
        DescriptiveStatistics pmlVolume = new DescriptiveStatistics();
        DescriptiveStatistics minDistCenter = new DescriptiveStatistics();
        double minDistCenterMean = Double.NaN;
        double minDistCenterSD = Double.NaN;
        double sdiF = Double.NaN;
        double nucVolume = new MeasureVolume(nucObj).getVolumeUnit();
        String nucIntDiffuse = nucObj.getComment();
        for (Object3DInt pmlObj : pmlPop.getObjects3DInt()) {
            pmlIntensity.addValue(new MeasureIntensity(pmlObj, imhPML).getValueMeasurement(MeasureIntensity.INTENSITY_SUM));
            pmlVolume.addValue(new MeasureVolume(pmlObj).getVolumeUnit());
            Measure2Distance dist = new Measure2Distance(nucObj, pmlObj);
            minDistCenter.addValue(dist.getValue(Measure2Distance.DIST_CC_UNIT));
        }
        if (pmlPop.getNbObjects() > 4) {
            sdiF = processF(pmlPop, nucObj, imgPML, imgName, outDirResults);
        }
        if (pmlPop.getNbObjects() > 2) {
            DescriptiveStatistics pmlDist = new DescriptiveStatistics();
            List<Double[]> distCC = pmlPop.getMeasurementsList(new MeasureDistancesCenter().getNamesMeasurement());
            for (Double dist : distCC.get(1)) {
                pmlDist.addValue(dist);
            }
            minDistCenterMean = pmlDist.getMean();
            minDistCenterSD = pmlDist.getStandardDeviation();

        }
        // compute statistics
        double pmlIntMean = pmlIntensity.getMean();
        double pmlIntSD = pmlIntensity.getStandardDeviation();
        double pmlIntMin = pmlIntensity.getMin();
        double pmlIntMax = pmlIntensity.getMax();
        double pmlVolumeMean = pmlVolume.getMean();
        double pmlVolumeSD = pmlVolume.getStandardDeviation();
        double pmlVolumeMin = pmlVolume.getMin();
        double pmlVolumeMax = pmlVolume.getMax();
        double pmlVolumeSum = pmlVolume.getSum();
        double minDistBorderMean = minDistCenter.getMean();
        double minDistBorderSD = minDistCenter.getStandardDeviation();

        results.write(imgName+"\t"+nucObj.getLabel()+"\t"+nucVolume+"\t"+pmlPop.getNbObjects()+"\t"+nucIntDiffuse+"\t"+pmlIntMean+"\t"+
                pmlIntSD+"\t"+pmlIntMin+"\t"+pmlIntMax+"\t"+pmlVolumeMean+"\t"+pmlVolumeSD+"\t"+pmlVolumeMin+"\t"+pmlVolumeMax+"\t"+pmlVolumeSum+"\t"+
                minDistCenterMean+"\t"+minDistCenterSD+"\t"+minDistBorderMean+"\t"+minDistBorderSD+"\t"+sdiF+"\n");
        results.flush();
    }
    
   
    
}

package Linked_Cells_Tools;


import Linked_CellsStardistOrion.StarDist2D;
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
import mcib3d.geom2.measurementsPopulation.MeasurePopulationColocalisation;
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
    // min max volume in microns^3 for cells
    private double minCell = 100;
    private double maxCell = 5000;

    // Stardist parameters
    private Object syncObject = new Object();
    private final double stardistPercentileBottom = 0.2;
    private final double stardistPercentileTop = 99.8;
    private final double stardistProbThreshCell = 0.5;
    private final double stardistOverlayThreshCell = 0.25;
    private final File modelsPath = new File(IJ.getDirectory("imagej")+File.separator+"models");
    private final String stardistOutput = "Label Image"; 
    public String stardistCellModel = "";

    public String[] channelNames = {"Cells1", "Cells2"};
    public Calibration cal = new Calibration();
    
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
        gd.addMessage("--- Stardist model ---", Font.getFont(Font.MONOSPACED), Color.blue);
        if (models.length > 0) {
            gd.addChoice("StarDist cell model :",models, models[0]);
        }
        else {
            gd.addMessage("No StarDist model found in Fiji !!", Font.getFont("Monospace"), Color.red);
            gd.addFileField("StarDist cell model :", stardistCellModel);
        }
//        gd.addMessage("Cells detection", Font.getFont("Monospace"), Color.blue);
//        gd.addNumericField("Min cell volume : ", minCell);
//        gd.addNumericField("Max cell volume : ", maxCell);
        gd.addMessage("Image calibration", Font.getFont("Monospace"), Color.blue);
        gd.addNumericField("XY Pixel size : ", cal.pixelWidth);
        gd.addNumericField("Z Pixel size  : ", cal.pixelDepth);
        gd.showDialog();
        if (gd.wasCanceled())
            canceled = true;
        String[] chChoices = new String[channelNames.length];
        for (int n = 0; n < chChoices.length; n++) 
            chChoices[n] = gd.getNextChoice();
        if (models.length > 0) {
            stardistCellModel = modelsPath+File.separator+gd.getNextChoice();
        }
        else {
            stardistCellModel = gd.getNextString();
        }
        if (stardistCellModel.isEmpty()) {
            IJ.error("No model specify !!");
            return(null);
        }
//        minCell = gd.getNextNumber();
//        maxCell = gd.getNextNumber();
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
    
     
    /** Look for all cells
    Do z slice by slice stardist 
    * return nuclei population
    */
   public Objects3DIntPopulation stardistCellsPop(ImagePlus imgCell, String stardistModel) throws IOException{
       ImagePlus img = null;
       // resize to be in a stardist-friendly scale
       int width = imgCell.getWidth();
       int height = imgCell.getHeight();
       float factor = 0.5f;
       boolean resized = false;
       if (imgCell.getWidth() > 512) {
           img = imgCell.resize((int)(width*factor), (int)(height*factor), 1, "none");
           resized = true;
       }
       else
           img = new Duplicator().run(imgCell);
       
       // Go StarDist
       File starDistModelFile = new File(stardistModel);
       StarDist2D star = new StarDist2D(syncObject, starDistModelFile);
       star.loadInput(img);
       star.setParams(stardistPercentileBottom, stardistPercentileTop, stardistProbThreshCell, stardistOverlayThreshCell, stardistOutput);
       star.run();
       flush_close(img);
       // label in 3D
       ImagePlus imgCells = (resized) ? star.associateLabels().resize(width, height, 1, "none") : star.associateLabels();
       imgCells.setCalibration(cal);
       Objects3DIntPopulation nPop = new Objects3DIntPopulation(ImageHandler.wrap(imgCells));
       //Objects3DIntPopulation nPop = sizeFilterPop(new Objects3DIntPopulation(ImageHandler.wrap(imgCells)), minCell, maxCell);
       flush_close(imgCells);
       return(nPop);
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
    * Find coloc within cells
    * return first population coloc
    */
    public Objects3DIntPopulation findColoc(Objects3DIntPopulation pop1, Objects3DIntPopulation pop2) {
        Objects3DIntPopulation colocPop = new Objects3DIntPopulation();
        if (pop1.getNbObjects() > 0 && pop2.getNbObjects() > 0) {
            MeasurePopulationColocalisation coloc = new MeasurePopulationColocalisation(pop1, pop2);
            //coloc.getResultsTableOnlyColoc().show("coloc");
            for (Object3DInt obj1 : pop1.getObjects3DInt()) {
                for (Object3DInt obj2 : pop2.getObjects3DInt()) {
                    double colocVal = coloc.getValueObjectsPair(obj1, obj2);
                    if (colocVal > 0) {
                        colocPop.addObject(obj1); 
                        break;
                    }
                }
            }
            colocPop.setVoxelSizeXY(cal.pixelWidth);
            colocPop.setVoxelSizeZ(cal.pixelDepth);
        }
        return(colocPop);
    }
    
}

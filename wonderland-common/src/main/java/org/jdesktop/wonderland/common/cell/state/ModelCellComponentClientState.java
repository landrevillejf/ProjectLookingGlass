package org.jdesktop.wonderland.common.cell.state;

public class ModelCellComponentClientState extends CellComponentClientState {
   private String deployedModelURL;
   private boolean pickable = true;
   private boolean lightingEnabled = true;
   private boolean backfaceCullingEnabled = true;
   private boolean graphOptimizationEnabled = true;
   private ModelCellComponentServerState.TransparencyMode transparencyMode;

   public ModelCellComponentClientState() {
      this.transparencyMode = ModelCellComponentServerState.TransparencyMode.DEFAULT;
   }

   public String getDeployedModelURL() {
      return this.deployedModelURL;
   }

   public void setDeployedModelURL(String deployedModelURL) {
      this.deployedModelURL = deployedModelURL;
   }

   public boolean isPickingEnabled() {
      return this.pickable;
   }

   public void setPickingEnabled(boolean pickable) {
      this.pickable = pickable;
   }

   public boolean isLightingEnabled() {
      return this.lightingEnabled;
   }

   public void setLightingEnabled(boolean lightingEnabled) {
      this.lightingEnabled = lightingEnabled;
   }

   public void setBackfaceCullingEnabled(boolean backfaceCullingEnabled) {
      this.backfaceCullingEnabled = backfaceCullingEnabled;
   }

   public boolean isBackfaceCullingEnabled() {
      return this.backfaceCullingEnabled;
   }

   public boolean isGraphOptimizationEnabled() {
      return this.graphOptimizationEnabled;
   }

   public void setGraphOptimizationEnabled(boolean graphOptimizationEnabled) {
      this.graphOptimizationEnabled = graphOptimizationEnabled;
   }

   public ModelCellComponentServerState.TransparencyMode getTransparencyMode() {
      return this.transparencyMode;
   }

   public void setTransparencyMode(ModelCellComponentServerState.TransparencyMode transparencyMode) {
      this.transparencyMode = transparencyMode;
   }
}

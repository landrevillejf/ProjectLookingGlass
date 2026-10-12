package org.jdesktop.wonderland.common.cell.state;

import java.io.Serializable;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import org.jdesktop.wonderland.common.cell.state.annotation.ServerState;

@XmlRootElement(
   name = "model-cell-component"
)
@ServerState
public class ModelCellComponentServerState extends CellComponentServerState implements Serializable {
   @XmlElement(
      name = "deployedModelURL"
   )
   public String deployedModelURL = null;
   @XmlElement(
      name = "PickingEnabled"
   )
   private boolean pickable = true;
   @XmlElement(
      name = "CollisionEnabled"
   )
   private boolean collidable = true;
   @XmlElement(
      name = "LightingEnabled"
   )
   private boolean lightingEnabled = true;
   @XmlElement(
      name = "BackfaceCullingEnabled"
   )
   private boolean backfaceCullingEnabled = true;
   @XmlElement(
      name = "GraphOptimizationEnabled"
   )
   private boolean graphOptimizationEnabled = true;
   @XmlElement(
      name = "TransparencyMode"
   )
   private TransparencyMode transparencyMode;

   public ModelCellComponentServerState() {
      this.transparencyMode = ModelCellComponentServerState.TransparencyMode.DEFAULT;
   }

   @XmlTransient
   public String getServerComponentClassName() {
      return "org.jdesktop.wonderland.server.cell.ModelCellComponentMO";
   }

   public CellComponentServerState clone(CellComponentServerState state) {
      ModelCellComponentServerState ret = (ModelCellComponentServerState)state;
      if (ret == null) {
         ret = new ModelCellComponentServerState();
      }

      ret.deployedModelURL = this.deployedModelURL;
      ret.pickable = this.pickable;
      ret.lightingEnabled = this.lightingEnabled;
      ret.backfaceCullingEnabled = this.backfaceCullingEnabled;
      ret.graphOptimizationEnabled = this.graphOptimizationEnabled;
      ret.transparencyMode = this.transparencyMode;
      return ret;
   }

   public CellComponentClientState setClientState(ModelCellComponentClientState state) {
      state.setDeployedModelURL(this.deployedModelURL);
      state.setPickingEnabled(this.pickable);
      state.setLightingEnabled(this.lightingEnabled);
      state.setBackfaceCullingEnabled(this.backfaceCullingEnabled);
      state.setGraphOptimizationEnabled(this.graphOptimizationEnabled);
      state.setTransparencyMode(this.transparencyMode);
      return state;
   }

   @XmlTransient
   public String getDeployedModelURL() {
      return this.deployedModelURL;
   }

   public void setDeployedModelURL(String deployedModelURL) {
      this.deployedModelURL = deployedModelURL;
   }

   @XmlTransient
   public boolean isPickingEnabled() {
      return this.pickable;
   }

   public void setPickingEnable(boolean pickable) {
      this.pickable = pickable;
   }

   /** @deprecated */
   @XmlTransient
   public boolean isCollisionEnabled() {
      return this.collidable;
   }

   /** @deprecated */
   public void setCollisionEnabled(boolean collidable) {
      this.collidable = collidable;
   }

   @XmlTransient
   public boolean isLightingEnabled() {
      return this.lightingEnabled;
   }

   public void setLightingEnabled(boolean lightingEnabled) {
      this.lightingEnabled = lightingEnabled;
   }

   @XmlTransient
   public boolean isBackfactCullingEnabled() {
      return this.backfaceCullingEnabled;
   }

   public void setBackfaceCullingEnabled(boolean backfaceCullingEnabled) {
      this.backfaceCullingEnabled = backfaceCullingEnabled;
   }

   @XmlTransient
   public boolean isGraphOptimizationEnabled() {
      return this.graphOptimizationEnabled;
   }

   public void setGraphOptimizationEnabled(boolean graphOptimizationEnabled) {
      this.graphOptimizationEnabled = graphOptimizationEnabled;
   }

   @XmlTransient
   public TransparencyMode getTransparencyMode() {
      return this.transparencyMode;
   }

   public void setTransparencyMode(TransparencyMode transparencyMode) {
      this.transparencyMode = transparencyMode;
   }

   public static enum TransparencyMode {
      DEFAULT,
      INVERSE,
      NONE;
   }
}

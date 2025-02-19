/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.graph_builder.annotation;

public class LevelAmbiguous extends GraphBuilderAnnotation {

    private static final long serialVersionUID = 1L;

    public static final String FMT = "Could not infer floor number for layer called '%s' at %s. " +
    		"Vertical movement will still be possible, but elevator cost might be incorrect. " +
    		"Consider an OSM level map.";
    
    final String layerName;

    final long osmNode;
    
    public LevelAmbiguous(String layerName, long osmNode){
    	this.layerName = layerName;
    	this.osmNode = osmNode;
    }
    
    @Override
    public String getMessage() {
        return String.format(FMT, layerName, osmNode);
    }

}

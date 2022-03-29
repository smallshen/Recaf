package me.coley.recaf.mapping;

import me.coley.recaf.Controller;
import me.coley.recaf.code.ClassInfo;
import me.coley.recaf.util.threading.ThreadPoolFactory;
import me.coley.recaf.util.threading.ThreadUtil;
import me.coley.recaf.workspace.resource.Resource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Mapping utility code.
 *
 * @author Matt Coley
 */
public class MappingUtils {
	/**
	 * Quick utility for applying mappings for operations like copy, rename, move.
	 * This does not update a {@link Controller}'s associated {@link MappingsManager}'s aggregated mappings.
	 *
	 * @param read
	 * 		Flags to use to parse classes in the resource.
	 * @param write
	 * 		Flags to use to write back classes to the resource.
	 * @param resource
	 * 		Resource to apply mappings to.
	 * @param mappings
	 * 		The mappings to apply.
	 *
	 * @return Names of the classes in the resource that had modifications as a result of the mapping operation.
	 */
	public static Set<String> applyMappingsWithoutAggregation(int read, int write,
															  Resource resource, Mappings mappings) {
		ExecutorService service = ThreadPoolFactory.newFixedThreadPool("Recaf mapping");
		Set<String> modifiedClasses = new HashSet<>();
		for (ClassInfo classInfo : new ArrayList<>(resource.getClasses().values())) {
			service.submit(() -> {
				String originalName = classInfo.getName();
				// Apply renamer
				ClassWriter cw = new ClassWriter(read);
				ClassReader cr = new ClassReader(classInfo.getValue());
				RemappingVisitor remapVisitor = new RemappingVisitor(cw, mappings);
				cr.accept(remapVisitor, write);
				// Update class if it has any modified references
				if (remapVisitor.hasMappingBeenApplied()) {
					modifiedClasses.add(classInfo.getName());
					ClassInfo updatedInfo = ClassInfo.read(cw.toByteArray());
					resource.getClasses().put(updatedInfo);
					// Remove old classes if they have been renamed
					if (!originalName.equals(updatedInfo.getName())) {
						resource.getClasses().remove(originalName);
					}
				}
			});
		}
		ThreadUtil.blockUntilComplete(service);
		return modifiedClasses;
	}

	/**
	 * Quick utility for applying mappings for operations like copy, rename, move.
	 *
	 * @param read
	 * 		Flags to use to parse classes in the resource.
	 * @param write
	 * 		Flags to use to write back classes to the resource.
	 * @param controller
	 * 		Controller to update aggregated mappings <i>(See: {@link MappingsManager})</i>
	 * @param resource
	 * 		Resource to apply mappings to.
	 * @param mappings
	 * 		The mappings to apply.
	 *
	 * @return Names of the classes in the resource that had modifications as a result of the mapping operation.
	 */
	public static Set<String> applyMappings(int read, int write, Controller controller,
											Resource resource, Mappings mappings) {
		// Check if mappings can be enriched with type look-ups
		if (mappings instanceof MappingsAdapter) {
			// If we have "Dog extends Animal" and both define "jump" this lets "Dog.jump()" see "Animal.jump()"
			// allowing mappings that aren't complete for their type hierarchies to be filled in.
			MappingsAdapter adapter = (MappingsAdapter) mappings;
			adapter.enableHierarchyLookup(controller.getServices().getInheritanceGraph());
		}
		Set<String> modifiedClasses = applyMappingsWithoutAggregation(read, write, resource, mappings);
		controller.getServices().getMappingsManager().updateAggregateMappings(mappings);
		return modifiedClasses;
	}
}
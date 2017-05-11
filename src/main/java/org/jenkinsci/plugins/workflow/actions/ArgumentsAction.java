/*
 * The MIT License
 *
 * Copyright (c) 2017, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.actions;

import com.google.common.primitives.Primitives;
import org.apache.commons.collections.CollectionUtils;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stores some or all of the arguments used to create and configure the {@link Step} executed by a {@link FlowNode}.
 * This allows you to inspect information supplied in the pipeline script and otherwise discarded at runtime.
 * Supplied argument values can be hidden and replaced with a {@link NotStoredReason} for security or performance.
 *
 * Important note: these APIs do not provide recursive guarantees that returned datastructures are immutable.
 */
public abstract class ArgumentsAction implements PersistentAction {

    /** Used as a placeholder marker for {@link Step} arguments not stored for various reasons. */
    public enum NotStoredReason {
        /** Denotes an unsafe value that cannot be stored/displayed due to sensitive info. */
        MASKED_VALUE,

        /** Denotes an object that is too big to retain, such as strings exceeding {@link #MAX_RETAINED_LENGTH} */
        OVERSIZE_VALUE
    }

    /** Largest String, Collection, or array size we'll retain -- provides a rough size limit. */
    public static final int MAX_RETAINED_LENGTH = 1024;

    /**
     * Check if an object is oversized to be stored as a step argument, including recursive checks for maps, arrays, and collections.
     * @param o Object to check, with null allowed since we may see null inputs
     * @param maxElements Max number of elements for a collection/map or characters in a string
     * @return True if object (or one of the contained objects) fails maxElements
     */
    public static boolean isOverSized(@CheckForNull Object o, final int maxElements) {
        if (o == null || Primitives.isWrapperType(o.getClass()) || o.getClass().isEnum()) {
            return false;
        }
        if (o instanceof CharSequence) {
            return ((CharSequence) o).length() > maxElements;
        }
        if ((o instanceof Map || o instanceof Collection || o.getClass().isArray())) {
            if (CollectionUtils.size(o) > maxElements) {
                return true;
            }
            if (o instanceof Collection) {
                for (Object element : (Collection)o) {
                    if (isOverSized(element, maxElements)) {
                        return true;
                    }
                }
            }
            if (o instanceof Object[]){
                for (Object element : (Object[])o) {
                    if (isOverSized(element, maxElements)) {
                        return true;
                    }
                }
            }
            if (o instanceof Map) {
                for(Map.Entry<?,?> entry : ((Map<?,?>)o).entrySet()) {
                    if (isOverSized((Object)(entry.getKey()), maxElements) || isOverSized((Object)(entry.getValue()), maxElements)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public String getIconFileName() {
        // TODO Add an icon and UI for inspecting a step's arguments
        return null;
    }

    @Override
    public String getDisplayName() {
        // TODO Once we have a UI and Jelly view, switch to "Step Arguments"
        return null;
    }

    @Override
    public String getUrlName() {
        // TODO Once we have a UI and view, switch to "stepArguments"
        return null;
    }

    /**
     * Get the map of arguments used to instantiate the {@link Step}, with a {@link NotStoredReason} instead of the argument value
     *  supplied in the executed pipeline step if that value is filtered for size or security.
     * @return The arguments for the {@link Step} as with {@link StepDescriptor#defineArguments(Step)}
     */
    @Nonnull
    public Map<String,Object> getArguments() {
        Map<String,Object> args = getArgumentsInternal();
        if (args.isEmpty()) {
            return Collections.<String,Object>emptyMap();
        } else {
            return Collections.unmodifiableMap(getArgumentsInternal());
        }
    }

    /**
     * Get the map of arguments supplied to instantiate the {@link Step} run in the {@link FlowNode} given
     * or null if the arguments were not stored or the FlowNode was not a step.
     *
     * Internally
     * @param n FlowNode to fetch Step arguments for (including placeholders for masked values).
     * @return
     */
    @Nonnull
    public static Map<String,Object> getArguments(@Nonnull FlowNode n) {
        ArgumentsAction aa = n.getPersistentAction(ArgumentsAction.class);
        return aa != null ? aa.getArguments() : Collections.<String,Object>emptyMap();
    }

    /**
     * Get just the fully stored, non-null arguments
     * This means the arguments with all {@link NotStoredReason} or null values removed
     * @return Map of all completely stored arguments
     */
    @Nonnull
    public Map<String, Object> getFilteredArguments() {
        Map<String, Object> internalArgs = this.getArgumentsInternal();
        if (internalArgs.size() == 0) {
            return Collections.<String,Object>emptyMap();
        }
        HashMap<String, Object> filteredArguments = new HashMap<String, Object>();
        for (Map.Entry<String, Object> entry : internalArgs.entrySet()) {
            if (entry.getValue() != null && !(entry.getValue() instanceof NotStoredReason)) {
                filteredArguments.put(entry.getKey(), entry.getValue());
            }
        }
        return filteredArguments;
    }

    /**
     * Get just the fully stored, non-null arguments
     * This means the arguments with all {@link NotStoredReason} or null values removed
     * @param n FlowNode to get arguments for
     * @return Map of all completely stored arguments
     */
    @Nonnull
    public static Map<String, Object> getFilteredArguments(@Nonnull FlowNode n) {
        ArgumentsAction act = n.getPersistentAction(ArgumentsAction.class);
        return act != null ? act.getFilteredArguments() : Collections.EMPTY_MAP;
    }

    /** Return a tidy string description for the step arguments, or null if none is present or we can't make one
     *  See {@link StepDescriptor#argumentsToString(Map)} for the rules
     */
    @CheckForNull
    public static String getStepArgumentsAsString(@Nonnull FlowNode n) {
        if (n instanceof StepNode) {
            StepDescriptor descriptor = ((StepNode) n).getDescriptor();
            if (descriptor != null) {  // Null if plugin providing descriptor was uninstalled
                Map<String, Object> filteredArgs = getFilteredArguments(n);
                return descriptor.argumentsToString(filteredArgs);
            }
        }
        return null;  // non-StepNode nodes can't have step arguments
    }

    /**
     * Return a fast view of internal arguments, without creating immutable wrappers
     * @return Internal arguments
     */
    @Nonnull
    protected abstract Map<String, Object> getArgumentsInternal();

    /**
     * Get the value of a argument, or null if not present/not stored.
     * Use {@link #getArgumentValueOrReason(String)} if you want to return the {@link NotStoredReason} rather than null.
     * @param argumentName Argument name of step to look up.
     * @return Argument value or null if not present/not stored.
     */
    @CheckForNull
    public Object getArgumentValue(@Nonnull String argumentName) {
        Object val = getArgumentValueOrReason(argumentName);
        return (val instanceof NotStoredReason) ? null : val;
    }

    /**
     * Get the argument value or its {@link NotStoredReason} if it has been intentionally omitted.
     * @param argumentName Name of step argument to find value for
     * @return Argument value, null if nonexistent/null, or NotStoredReason if it existed by was masked out.
     */
    @CheckForNull
    public Object getArgumentValueOrReason(@Nonnull String argumentName) {
        Object ob = getArgumentsInternal().get(argumentName);
        if (ob instanceof Map) {
            return Collections.unmodifiableMap((Map)ob);
        } else if (ob instanceof Set) {
            return Collections.unmodifiableSet((Set)ob);
        } else if (ob instanceof List) {
            return Collections.unmodifiableList((List)ob);
        } else if (ob instanceof Collection) {
            return Collections.unmodifiableCollection((Collection)ob);
        }
        return ob;
    }

    /**
     * Check if any of the named arguments in the supplied list of arguments has a {@link NotStoredReason} placeholder.
     * Useful for the default implementation of {@link #isUnmodifiedArguments()} or overrides.
     * @param namedArgs Set of argument name and argument value pairs, as from {@link StepDescriptor#defineArguments(Step)}
     * @return True if no argument has a {@link NotStoredReason} placeholder value, else false
     */
    static boolean checkArgumentsLackPlaceholders(@Nonnull Map<String,Object> namedArgs) {
        for(Object ob : namedArgs.values()) {
            if (ob instanceof NotStoredReason) {
                return false;
            }
        }
        return true;
    }

    /**
     * Test if {@link Step} arguments are persisted in an unaltered form.
     * @return True if full arguments are retained, false if some have been removed for security, size, or other reasons.
     */
    public boolean isUnmodifiedArguments() {
        // Cacheable, but arguments lists will be quite short and this is unlikely to get invoked heavily.
        return checkArgumentsLackPlaceholders(this.getArgumentsInternal());
    }
}

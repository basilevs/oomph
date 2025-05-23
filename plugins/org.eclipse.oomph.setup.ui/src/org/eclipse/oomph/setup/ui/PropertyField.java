/*
 * Copyright (c) 2014-2017 Eike Stepper (Loehne, Germany) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *    Eike Stepper - initial API and implementation
 *    Ericsson AB (Julian Enoch) - Bug 425815 - Expand the functionality for PASSWORD variables
 */
package org.eclipse.oomph.setup.ui;

import org.eclipse.oomph.base.BaseFactory;
import org.eclipse.oomph.jreinfo.JRE;
import org.eclipse.oomph.jreinfo.JREFilter;
import org.eclipse.oomph.jreinfo.JREManager;
import org.eclipse.oomph.jreinfo.ui.JREController;
import org.eclipse.oomph.preferences.util.PreferencesUtil;
import org.eclipse.oomph.setup.SetupFactory;
import org.eclipse.oomph.setup.VariableChoice;
import org.eclipse.oomph.setup.VariableTask;
import org.eclipse.oomph.setup.VariableType;
import org.eclipse.oomph.setup.internal.core.SetupCorePlugin;
import org.eclipse.oomph.setup.internal.core.util.Authenticator;
import org.eclipse.oomph.util.IOUtil;
import org.eclipse.oomph.util.OS;
import org.eclipse.oomph.util.Request;
import org.eclipse.oomph.util.StringUtil;

import org.eclipse.emf.common.ui.dialogs.WorkspaceResourceDialog;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.plugin.EcorePlugin;
import org.eclipse.emf.edit.provider.IItemFontProvider;
import org.eclipse.emf.edit.ui.provider.ExtendedFontRegistry;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.events.VerifyListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Eike Stepper
 */
public abstract class PropertyField
{
  public static final int NUM_COLUMNS = 3;

  private static final String EMPTY = ""; //$NON-NLS-1$

  private static final Pattern JRE_LOCATION_VARIABLE_PATTERN = Pattern.compile("\\$\\{jre\\.location-([0-9]+)(?:\\.([0-9]+))?\\}"); //$NON-NLS-1$

  public static PropertyField createField(final VariableTask variable)
  {
    PropertyField field = createField(variable.getType(), variable.getChoices());

    String label = variable.getLabel();
    if (StringUtil.isEmpty(label))
    {
      label = variable.getName();
    }

    field.setLabelText(label);
    field.setToolTip(variable.getDescription());
    field.setLink(variable.getLink());

    GridData gridData = field.getLabelGridData();
    gridData.widthHint = 150;

    return field;
  }

  public static PropertyField createField(VariableType type, List<? extends VariableChoice> choices)
  {
    switch (type)
    {
      case JRE:
        if (choices.isEmpty())
        {
          EPackage ePackage = EPackage.Registry.INSTANCE.getEPackage("http://www.eclipse.org/oomph/setup/jdt/1.0"); //$NON-NLS-1$
          if (ePackage != null)
          {
            EClassifier eClassifier = ePackage.getEClassifier("JRETask"); //$NON-NLS-1$
            if (eClassifier != null)
            {
              EList<EAnnotation> eAnnotations = eClassifier.getEAnnotations();
              if (!eAnnotations.isEmpty())
              {
                String version = eAnnotations.get(eAnnotations.size() - 1).getDetails().get("version"); //$NON-NLS-1$
                if (version != null && version.startsWith("JavaSE-")) //$NON-NLS-1$
                {
                  try
                  {
                    return new JREField(new JREFilter(Integer.parseInt(version.substring("JavaSE-".length())), 0, null), choices); //$NON-NLS-1$
                  }
                  catch (RuntimeException ex)
                  {
                    //$FALL-THROUGH$
                  }
                }
              }
            }
          }

          JREField jreField = new JREField(new JREFilter(24, 0, null), choices);
          return jreField;
        }

        for (VariableChoice choice : choices)
        {
          String value = choice.getValue();
          if (value != null)
          {
            Matcher matcher = JRE_LOCATION_VARIABLE_PATTERN.matcher(value);
            if (matcher.matches())
            {
              int major = Integer.valueOf(matcher.group(1));
              int minor = matcher.group(2) == null ? -1 : Integer.valueOf(matcher.group(2)) - 1;
              if (minor == -1)
              {
                if (major == 9)
                {
                  return new JREField(new JREFilter(1, 8, null), choices);
                }

                return new JREField(new JREFilter(major - 1, null, null), choices);
              }

              return new JREField(new JREFilter(major, minor, null), choices);
            }
          }
        }

        return createField(VariableType.FOLDER, choices);

      case FOLDER:
        FolderField folderField = new FolderField(choices);
        folderField.setDialogText(Messages.PropertyField_folderField_text);
        folderField.setDialogMessage(Messages.PropertyField_folderField_message);
        return folderField;

      case FILE:
        FileField fileField = new FileField(choices);
        fileField.setDialogText(Messages.PropertyField_fileField_text);
        return fileField;

      case CONTAINER:
        ContainerField containerField = new ContainerField(choices);
        containerField.setDialogText(Messages.PropertyField_containerField_text);
        containerField.setDialogMessage(Messages.PropertyField_containerField_message);
        return containerField;

      case TEXT:
        TextField textField = new TextField(choices)
        {
          @Override
          protected Text createText(Composite parent, int style)
          {
            return super.createText(parent, style | SWT.MULTI | SWT.V_SCROLL);
          }
        };

        textField.getControlGridData().heightHint = 50;
        return textField;

      case PASSWORD:
        return new AuthenticatedField();

      case BOOLEAN:
        return new CheckboxField();
    }

    return new TextField(choices);
  }

  private final GridData labelGridData = new GridData(SWT.RIGHT, SWT.TOP, false, false);

  private final GridData controlGridData = new GridData(SWT.FILL, SWT.TOP, true, false);

  private final GridData helperGridData = new GridData(SWT.FILL, SWT.TOP, false, false);

  private final List<ValueListener> valueListeners = new CopyOnWriteArrayList<>();

  private String value = EMPTY;

  private String labelText;

  private URI link;

  private String toolTip;

  private Label label;

  private Control helper;

  private boolean enabled = true;

  private OS os = OS.INSTANCE;

  public PropertyField()
  {
    this(null);
  }

  public PropertyField(String labelText)
  {
    this.labelText = labelText;
    setValue(null);
  }

  @Override
  public String toString()
  {
    return getClass().getSimpleName() + "[label=" + labelText + ", value=" + value + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
  }

  public final String getLabelText()
  {
    return labelText;
  }

  public final void setLabelText(String labelText)
  {
    if (labelText.endsWith(":")) //$NON-NLS-1$
    {
      labelText = labelText.substring(0, labelText.length() - 1);
    }

    this.labelText = labelText.trim();
  }

  public final URI getLink()
  {
    return link;
  }

  public final void setLink(URI link)
  {
    this.link = link;
  }

  public final void setBold(boolean bold)
  {
    label.setFont(ExtendedFontRegistry.INSTANCE.getFont(label.getFont(), bold ? IItemFontProvider.BOLD_FONT : IItemFontProvider.NORMAL_FONT));
  }

  public final String getToolTip()
  {
    return toolTip;
  }

  public final void setToolTip(String toolTip)
  {
    this.toolTip = toolTip;
  }

  public final String getValue()
  {
    return value;
  }

  public abstract String getDefaultValue();

  public final void setValue(String value)
  {
    setValue(value, true);
  }

  public final void setValue(String value, boolean notify)
  {
    setValue(value, notify, true);
  }

  public final void setValue(String value, boolean notify, boolean transfer)
  {
    if (value == null)
    {
      value = EMPTY;
    }

    String oldValue = this.value;
    if (!oldValue.equals(value))
    {
      this.value = value;

      if (transfer)
      {
        String controlValue = getControlValue();
        if (!controlValue.equals(value))
        {
          transferValueToControl(value, false);
        }
      }

      if (notify)
      {
        notifyValueListeners(oldValue, value);
      }
    }
  }

  public OS getOS()
  {
    return os;
  }

  public void setOS(OS os)
  {
    this.os = os;
  }

  public final void addValueListener(ValueListener listener)
  {
    checkValueListener(listener);
    valueListeners.add(listener);
  }

  public final void removeValueListener(ValueListener listener)
  {
    checkValueListener(listener);
    valueListeners.remove(listener);
  }

  public final void fill(final Composite parent)
  {
    checkParentLayout(parent);

    label = new Label(parent, SWT.WRAP);
    label.setLayoutData(labelGridData);
    if (labelText != null)
    {
      if (link != null)
      {
        label.setText(labelText + ": \uD83D\uDD17"); //$NON-NLS-1$
        label.setCursor(label.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        label.addMouseListener(new MouseAdapter()
        {
          @Override
          public void mouseDown(MouseEvent e)
          {
            OS.INSTANCE.openSystemBrowser(link.toString());
          }
        });
      }
      else
      {
        label.setText(labelText + ":"); //$NON-NLS-1$
      }
    }

    Control control = createControl(parent);
    Control mainControl = getMainControl();
    mainControl.setLayoutData(controlGridData);

    if (toolTip != null && toolTip.length() != 0)
    {
      label.setToolTipText(toolTip);
      control.setToolTipText(toolTip);
      mainControl.setToolTipText(toolTip);
    }

    helper = createHelper(parent);
    if (helper == null)
    {
      helper = new Label(parent, SWT.NONE);
    }
    else
    {
      helper = getMainHelper();
    }

    helper.setLayoutData(helperGridData);
    setEnabled(enabled);
    transferValueToControl(value, true);
  }

  public final Label getLabel()
  {
    return label;
  }

  public final GridData getLabelGridData()
  {
    return labelGridData;
  }

  public final GridData getControlGridData()
  {
    return controlGridData;
  }

  public final Control getHelper()
  {
    return helper;
  }

  public final GridData getHelperGridData()
  {
    return helperGridData;
  }

  public final void setFocus()
  {
    Control control = getControl();
    if (control != null)
    {
      control.setFocus();
    }
  }

  public final boolean isEnabled()
  {
    return enabled;
  }

  public final void setEnabled(boolean enabled)
  {
    this.enabled = enabled;

    if (label != null)
    {
      label.setEnabled(enabled);
    }

    setControlEnabled(enabled);

    Control mainHelper = getMainHelper();
    if (mainHelper != null)
    {
      mainHelper.setEnabled(enabled);
    }
  }

  protected void setControlEnabled(boolean enabled)
  {
    Control mainControl = getMainControl();
    if (mainControl != null)
    {
      mainControl.setEnabled(enabled);
    }
  }

  protected Control getMainControl()
  {
    return getControl();
  }

  protected Control getMainHelper()
  {
    return helper;
  }

  public abstract Control getControl();

  protected abstract String getControlValue();

  protected abstract void transferValueToControl(String value, boolean force);

  protected abstract Control createControl(Composite parent);

  protected Control createHelper(Composite parent)
  {
    return null;
  }

  private void checkParentLayout(Composite parent)
  {
    Layout layout = parent.getLayout();
    if (layout instanceof GridLayout)
    {
      GridLayout gridLayout = (GridLayout)layout;
      if (gridLayout.numColumns == NUM_COLUMNS)
      {
        return;
      }
    }

    throw new IllegalArgumentException("Parent must have a GridLayout with 3 columns"); //$NON-NLS-1$
  }

  private void checkValueListener(ValueListener listener)
  {
    if (listener == null)
    {
      throw new IllegalArgumentException("listener is null"); //$NON-NLS-1$
    }
  }

  private void notifyValueListeners(String oldValue, String newValue)
  {
    for (ValueListener listener : valueListeners)
    {
      try
      {
        listener.valueChanged(oldValue, newValue);
      }
      catch (Exception ex)
      {
        SetupUIPlugin.INSTANCE.log(ex);
      }
    }
  }

  /**
   * @author Eike Stepper
   */
  public interface ValueListener
  {
    public void valueChanged(String oldValue, String newValue) throws Exception;
  }

  /**
   * @author Eike Stepper
   */
  public static class CheckboxField extends PropertyField
  {
    private Button button;

    public CheckboxField()
    {
      this(null);
    }

    public CheckboxField(String labelText)
    {
      super(labelText);
    }

    @Override
    public Control getControl()
    {
      return button;
    }

    @Override
    public String getDefaultValue()
    {
      return "false"; //$NON-NLS-1$
    }

    @Override
    protected String getControlValue()
    {
      return Boolean.toString(button != null && button.getSelection());
    }

    @Override
    protected void transferValueToControl(String value, boolean force)
    {
      if (button != null)
      {
        button.setSelection(Boolean.valueOf(value));
      }
    }

    @Override
    protected Control createControl(Composite parent)
    {
      button = new Button(parent, SWT.CHECK);
      button.addSelectionListener(new SelectionAdapter()
      {
        @Override
        public void widgetSelected(SelectionEvent e)
        {
          String value = getControlValue();
          if (!value.equals(getValue()))
          {
            setValue(value);
          }
        }
      });

      String toolTip = getToolTip();
      if (toolTip != null)
      {
        button.setToolTipText(toolTip);
      }

      return button;
    }

    protected String computeLinkedValue(String thisValue, String linkValue)
    {
      return linkValue;
    }
  }

  /**
   * @author Eike Stepper
   */
  public static class TextField extends PropertyField
  {
    protected static final String DEFAULT_MARKER = "default"; //$NON-NLS-1$

    private final boolean secret;

    private PropertyField linkField;

    private Composite mainControl;

    private Text text;

    private ComboViewer comboViewer;

    private ToolItem linkButton;

    private boolean linked = true;

    private List<? extends VariableChoice> choices;

    private FocusHandler focusHandler;

    public TextField()
    {
      this(null, false);
    }

    public TextField(boolean secret)
    {
      this(null, secret);
    }

    public TextField(String labelText)
    {
      this(labelText, false);
    }

    public TextField(String labelText, boolean secret)
    {
      this(labelText, secret, null);
    }

    public TextField(List<? extends VariableChoice> choices)
    {
      this(null, choices);
    }

    public TextField(String labelText, List<? extends VariableChoice> choices)
    {
      this(labelText, false, choices);
    }

    private TextField(String labelText, boolean secret, List<? extends VariableChoice> choices)
    {
      super(labelText);
      this.secret = secret;
      this.choices = choices;
    }

    @Override
    public String getDefaultValue()
    {
      if (choices == null || choices.isEmpty())
      {
        return ""; //$NON-NLS-1$
      }

      for (VariableChoice choice : choices)
      {
        if (choice.getAnnotation(DEFAULT_MARKER) != null)
        {
          return choice.getValue();
        }
      }

      return choices.get(0).getValue();
    }

    public final PropertyField getLinkField()
    {
      return linkField;
    }

    public final void setLinkField(PropertyField field)
    {
      linkField = field;
    }

    public final boolean isLinked()
    {
      return linked;
    }

    public final void setLinked(boolean linked)
    {
      this.linked = linked;

      if (linkButton != null)
      {
        String path = linked ? "linked.gif" : "icons/unlinked"; //$NON-NLS-1$ //$NON-NLS-2$
        Image image = SetupUIPlugin.INSTANCE.getSWTImage(path);

        linkButton.setImage(image);
        linkButton.setSelection(linked);

        if (linked)
        {
          setLinkedValue(linkField.getValue());
        }
      }
    }

    public final void setLinkedFromValue()
    {
      String thisValue = getValue();
      String linkValue = linkField.getValue();
      setLinked(thisValue.length() == 0 && linkValue.length() == 0 || thisValue.equals(computeLinkedValue(thisValue, linkValue)));
    }

    @Override
    protected Control getMainControl()
    {
      if (mainControl != null)
      {
        return mainControl;
      }

      return super.getMainControl();
    }

    @Override
    public Control getControl()
    {
      return text != null ? text : comboViewer.getCombo();
    }

    protected ComboViewer getComboViewer()
    {
      return comboViewer;
    }

    @Override
    protected String getControlValue()
    {
      return text != null ? secret ? PreferencesUtil.encrypt(text.getText()) : text.getText() : comboViewer.getCombo().getText();
    }

    @Override
    protected void transferValueToControl(String value, boolean force)
    {
      if (text != null)
      {
        String actualValue = secret ? PreferencesUtil.decrypt(value) : value;
        if (!force && text.isFocusControl())
        {
          // If the controls has focus, the user is still actively changing it
          // so we don't want to replace the empty string with the default value until the control loses focus.
          if (focusHandler == null)
          {
            focusHandler = new FocusHandler();
            text.addFocusListener(focusHandler);
          }

          // Remember which value we want to set when the control loses focus.
          // This value will be set only if the control's text is still empty.
          focusHandler.setValue(actualValue);
        }
        else
        {
          text.setText(actualValue);
          text.setSelection(actualValue.length());
        }
      }
      else
      {
        comboViewer.getCombo().setText(value);
        updateToolTip(value);
      }
    }

    private void updateToolTip(String value)
    {
      if (!StringUtil.isEmpty(value))
      {
        for (VariableChoice choice : choices)
        {
          if (value.equals(choice.getValue()))
          {
            comboViewer.getCombo().setToolTipText(choice.getLabel());
            return;
          }
        }
      }

      String toolTip = getToolTip();
      comboViewer.getCombo().setToolTipText(toolTip == null ? "" : toolTip); //$NON-NLS-1$
    }

    @Override
    protected Control createControl(Composite parent)
    {
      if (linkField == null)
      {
        return createControlHelper(parent);
      }

      GridLayout mainLayout = new GridLayout(2, false);
      mainLayout.marginWidth = 0;
      mainLayout.marginHeight = 0;
      mainLayout.horizontalSpacing = 0;

      mainControl = new Composite(parent, SWT.NULL)
      {
        @Override
        public void setEnabled(boolean enabled)
        {
          if (text != null)
          {
            text.setEnabled(enabled);
          }
          else
          {
            super.setEnabled(enabled);

            Control[] children = getChildren();
            for (int i = 0; i < children.length; i++)
            {
              Control child = children[i];
              child.setEnabled(enabled);
            }
          }
        }
      };
      mainControl.setLayout(mainLayout);

      Control control = createControlHelper(mainControl);
      control.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

      ToolBar toolBar = new ToolBar(mainControl, SWT.FLAT | SWT.NO_FOCUS);
      toolBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));

      linkButton = new ToolItem(toolBar, SWT.PUSH);
      linkButton.setToolTipText(NLS.bind(Messages.PropertyField_linkButton_tooltip, linkField.getLabelText()));
      linkButton.addSelectionListener(new SelectionAdapter()
      {
        @Override
        public void widgetSelected(SelectionEvent e)
        {
          setLinked(!linked);
        }
      });

      linkField.addValueListener(new ValueListener()
      {
        @Override
        public void valueChanged(String oldValue, String newValue) throws Exception
        {
          if (linked)
          {
            setLinkedValue(newValue);
          }
        }
      });

      setLinkedFromValue();

      return control;
    }

    protected String computeLinkedValue(String thisValue, String linkValue)
    {
      return linkValue;
    }

    private void setLinkedValue(String newValue)
    {
      String thisValue = getValue();
      String value = computeLinkedValue(thisValue, newValue);
      setValue(value);
    }

    private Control createControlHelper(Composite parent)
    {
      return choices == null || choices.isEmpty() ? createText(parent) : createCombo(parent);
    }

    private Text createText(Composite parent)
    {
      int style = SWT.BORDER;
      if (secret)
      {
        style |= SWT.PASSWORD;
      }

      text = createText(parent, style);

      String toolTip = getToolTip();
      if (toolTip != null)
      {
        text.setToolTipText(toolTip);
      }

      text.addModifyListener(new ModifyListener()
      {
        @Override
        public void modifyText(ModifyEvent e)
        {
          String value = text.getText();
          if (secret)
          {
            value = PreferencesUtil.encrypt(value);
          }

          if (!value.equals(getValue()))
          {
            setValue(value);

            if (linkButton != null && linked)
            {
              setLinkedFromValue();
            }
          }
        }
      });

      return text;
    }

    protected Text createText(Composite parent, int style)
    {
      return new Text(parent, style);
    }

    protected Combo createCombo(Composite parent)
    {
      comboViewer = new ComboViewer(parent, SWT.BORDER);

      final LabelProvider labelProvider = new LabelProvider()
      {
        @Override
        public String getText(Object element)
        {
          VariableChoice choice = (VariableChoice)element;
          String label = choice.getLabel();
          if (StringUtil.isEmpty(label))
          {
            return StringUtil.safe(choice.getValue());
          }

          return label;
        }
      };
      comboViewer.setLabelProvider(labelProvider);

      comboViewer.setContentProvider(new ArrayContentProvider());
      comboViewer.setInput(choices);
      comboViewer.getCombo().addModifyListener(new ModifyListener()
      {
        @Override
        public void modifyText(ModifyEvent e)
        {
          String value = comboViewer.getCombo().getText();
          setValue(value);
          updateToolTip(value);
        }
      });

      comboViewer.getCombo().addVerifyListener(new VerifyListener()
      {
        @Override
        public void verifyText(VerifyEvent e)
        {
          if (e.character == 0)
          {
            for (VariableChoice choice : choices)
            {
              if (labelProvider.getText(choice).equals(e.text))
              {
                e.text = choice.getValue();
                break;
              }
            }
          }
        }
      });

      Combo control = comboViewer.getCombo();
      control.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
      return control;
    }

    private class FocusHandler extends FocusAdapter
    {
      private String value;

      @Override
      public void focusLost(FocusEvent e)
      {
        // Forget about and remove this handler.
        focusHandler = null;
        text.removeFocusListener(this);

        // If the text is still empty, transfer the value to it.
        if (text.getText().length() == 0)
        {
          text.setText(value);
        }
      }

      public void setValue(String value)
      {
        this.value = value;
      }
    }
  }

  /**
   * @author Eike Stepper
   */
  public static class TextButtonField extends TextField
  {
    private String buttonText;

    public TextButtonField()
    {
    }

    public TextButtonField(boolean secret)
    {
      super(secret);
    }

    public TextButtonField(String labelText)
    {
      super(labelText);
    }

    public TextButtonField(String labelText, boolean secret)
    {
      super(labelText, secret);
    }

    public TextButtonField(String labelText, List<? extends VariableChoice> choices)
    {
      super(labelText, choices);
    }

    public final String getButtonText()
    {
      return buttonText;
    }

    public final void setButtonText(String buttonText)
    {
      this.buttonText = buttonText;
    }

    @Override
    protected Button createHelper(Composite parent)
    {
      final Button button = new Button(parent, SWT.NONE);
      if (buttonText != null)
      {
        button.setText(buttonText);
      }

      button.addSelectionListener(new SelectionAdapter()
      {
        @Override
        public void widgetSelected(SelectionEvent e)
        {
          helperButtonSelected(e);
        }
      });

      return button;
    }

    protected void helperButtonSelected(SelectionEvent e)
    {
    }
  }

  /**
   * @author Eike Stepper
   */
  public static class FolderField extends TextButtonField
  {
    private String dialogText;

    private String dialogMessage;

    public FolderField()
    {
      this(null, null);
    }

    public FolderField(String labelText)
    {
      this(labelText, null);
    }

    public FolderField(List<? extends VariableChoice> choices)
    {
      this(null, choices);
    }

    public FolderField(String labelText, List<? extends VariableChoice> choices)
    {
      super(labelText, choices);
      setButtonText(Messages.PropertyField_folderField_buttonText);
    }

    public final String getDialogText()
    {
      return dialogText;
    }

    public final void setDialogText(String dialogText)
    {
      this.dialogText = dialogText;
    }

    public final String getDialogMessage()
    {
      return dialogMessage;
    }

    public final void setDialogMessage(String dialogMessage)
    {
      this.dialogMessage = dialogMessage;
    }

    @Override
    protected void helperButtonSelected(SelectionEvent e)
    {
      Shell shell = getHelper().getShell();
      DirectoryDialog dialog = new DirectoryDialog(shell);
      if (dialogText != null)
      {
        dialog.setText(dialogText);
      }

      if (dialogMessage != null)
      {
        dialog.setMessage(dialogMessage);
      }

      String value = getValue();
      if (value.length() != 0)
      {
        try
        {
          File existingFolder = IOUtil.getExistingFolder(new File(value));
          if (existingFolder != null)
          {
            dialog.setFilterPath(existingFolder.getAbsolutePath());
          }
        }
        catch (Exception ex)
        {
          SetupUIPlugin.INSTANCE.log(ex, IStatus.WARNING);
        }
      }

      String dir = dialog.open();
      if (dir != null)
      {
        transferValueToControl(dir, true);
      }
    }
  }

  /**
   * @author Eike Stepper
   */
  public static class FileField extends TextButtonField
  {
    private String dialogText;

    public FileField()
    {
      this(null, null);
    }

    public FileField(String labelText)
    {
      this(labelText, null);
    }

    public FileField(List<? extends VariableChoice> choices)
    {
      this(null, choices);
    }

    public FileField(String labelText, List<? extends VariableChoice> choices)
    {
      super(labelText, choices);
      setButtonText(Messages.PropertyField_fileField_buttonText);
    }

    public final String getDialogText()
    {
      return dialogText;
    }

    public final void setDialogText(String dialogText)
    {
      this.dialogText = dialogText;
    }

    @Override
    protected void helperButtonSelected(SelectionEvent e)
    {
      Shell shell = getHelper().getShell();
      FileDialog dialog = new FileDialog(shell);
      if (dialogText != null)
      {
        dialog.setText(dialogText);
      }

      String value = getValue();
      if (value.length() != 0)
      {
        dialog.setFilterPath(value);
      }

      String dir = dialog.open();
      if (dir != null)
      {
        transferValueToControl(dir, true);
      }
    }
  }

  /**
   * @author Ed Merks
   */
  public static class ContainerField extends TextButtonField
  {
    private String dialogText;

    private String dialogMessage;

    public ContainerField()
    {
      this(null, null);
    }

    public ContainerField(String labelText)
    {
      this(labelText, null);
    }

    public ContainerField(List<? extends VariableChoice> choices)
    {
      this(null, choices);
    }

    public ContainerField(String labelText, List<? extends VariableChoice> choices)
    {
      super(labelText, choices);
      setButtonText(Messages.PropertyField_containerField_buttonText);
    }

    public final String getDialogText()
    {
      return dialogText;
    }

    public final void setDialogText(String dialogText)
    {
      this.dialogText = dialogText;
    }

    public final String getDialogMessage()
    {
      return dialogMessage;
    }

    public final void setDialogMessage(String dialogMessage)
    {
      this.dialogMessage = dialogMessage;
    }

    @Override
    protected void helperButtonSelected(SelectionEvent e)
    {
      Shell shell = getHelper().getShell();
      Object[] initialSelection = getInitialSelection();
      IContainer[] folders = WorkspaceResourceDialog.openFolderSelection(shell, getDialogText(), getDialogMessage(), false, initialSelection, null);
      if (folders.length > 0)
      {
        transferValueToControl(folders[0].getFullPath().toString(), true);
      }
    }

    private Object[] getInitialSelection()
    {
      try
      {
        String value = getValue();
        if (!StringUtil.isEmpty(value))
        {
          Path path = new Path(value);
          if (!path.isEmpty())
          {
            if (path.segmentCount() == 1)
            {
              return new Object[] { EcorePlugin.getWorkspaceRoot().getProject(path.segment(0)) };
            }

            return new Object[] { EcorePlugin.getWorkspaceRoot().getFolder(path) };
          }
        }
      }
      catch (Exception ex)
      {
        //$FALL-THROUGH$
      }

      return null;
    }
  }

  /**
   * @author Ed Merks
   */
  public static class JREField extends TextButtonField
  {
    private JREFilter jreFilter;

    public JREField(JREFilter jreFilter, List<? extends VariableChoice> choices)
    {
      super(null, getJREChoices(jreFilter));
      this.jreFilter = jreFilter;

      setButtonText(Messages.PropertyField_jreField_buttonText);
    }

    private static List<? extends VariableChoice> getJREChoices(JREFilter jreFilter)
    {
      List<VariableChoice> choices = new ArrayList<>();
      Map<File, JRE> jres = JREManager.INSTANCE.getJREs(jreFilter);
      if (!jres.isEmpty())
      {
        Map<Integer, VariableChoice> majorChoices = jreFilter == null ? null : new LinkedHashMap<>();
        for (Map.Entry<File, JRE> entry : jres.entrySet())
        {
          VariableChoice choice = SetupFactory.eINSTANCE.createVariableChoice();
          String folder = entry.getKey().toString();
          choice.setValue(folder);
          JRE jre = entry.getValue();
          choice.setLabel((jre.isJDK() ? "JDK " : "JRE ") + jre.getMajor() + "." + jre.getMinor() + "." + jre.getMicro() + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
              + NLS.bind(Messages.PropertyField_jreField_bitness, jre.getBitness()) + " -- " + folder); //$NON-NLS-1$
          choices.add(choice);
          if (majorChoices != null)
          {
            majorChoices.putIfAbsent(jre.getMajor(), choice);
          }
        }

        if (majorChoices != null)
        {
          new ArrayList<>(majorChoices.values()).get(majorChoices.size() - 1).getAnnotations().add(BaseFactory.eINSTANCE.createAnnotation(DEFAULT_MARKER));
        }
      }

      return choices;
    }

    @Override
    protected void helperButtonSelected(SelectionEvent e)
    {
      JREController jreController = new JREController(null, null, Request.Handler.SYSTEM_BROWSER)
      {
        @Override
        protected Shell getShell()
        {
          return getHelper().getShell();
        }

        @Override
        protected JRE getDefaultSelection()
        {
          String controlValue = getControlValue();
          return JREManager.INSTANCE.getJREs().get(new File(controlValue));
        }

        @Override
        protected void jreChanged(JRE jre)
        {
          if (jre != null && (jreFilter == null || jre.isMatch(jreFilter)))
          {
            transferValueToControl(jre.getJavaHome().getPath(), true);
          }
        }

        @Override
        protected JREFilter createJREFilter()
        {
          return jreFilter;
        }
      };

      jreController.setOS(getOS());
      jreController.refresh();
      jreController.configureJREs();
    }
  }

  /**
   * @author Ed Merks
   */
  public static class AuthenticatedField extends TextButtonField
  {
    private Set<Authenticator> authenticators = new LinkedHashSet<>();

    public AuthenticatedField()
    {
      super(true);
      setButtonText(Messages.PropertyField_authenticatedField_buttonText);
    }

    public void clear()
    {
      authenticators.clear();
      getHelper().setEnabled(false);
    }

    public void addAll(Set<? extends Authenticator> authenticators)
    {
      this.authenticators.addAll(authenticators);
      getHelper().setEnabled(!this.authenticators.isEmpty());

      StringBuilder toolTip = new StringBuilder();
      for (Authenticator authenticator : this.authenticators)
      {
        if (toolTip.length() != 0)
        {
          toolTip.append("\n"); //$NON-NLS-1$
        }

        toolTip.append(authenticator.getMessage(IStatus.INFO));
      }

      getHelper().setToolTipText(toolTip.toString());
    }

    @Override
    protected void helperButtonSelected(SelectionEvent e)
    {
      String pluginID = SetupCorePlugin.INSTANCE.getSymbolicName();
      MultiStatus status = new MultiStatus(pluginID, 0, Messages.PropertyField_authenticatedField_authenticationStatus, null);
      for (Authenticator authenticator : authenticators)
      {
        int severity = authenticator.validate();
        status.add(new Status(severity == IStatus.OK ? IStatus.INFO : severity, pluginID, authenticator.getMessage(severity)));
      }

      IStatus finalStatus = status;
      switch (status.getSeverity())
      {
        case IStatus.INFO:
        {
          status = new MultiStatus(pluginID, 0, Messages.PropertyField_authenticatedField_status_authenticated, null);
          status.addAll(finalStatus);
          break;
        }
        case IStatus.WARNING:
        {
          status = new MultiStatus(pluginID, 0, Messages.PropertyField_authenticatedField_status_cannotAuthenticate, null);
          status.addAll(finalStatus);
          break;
        }
        case IStatus.ERROR:
        {
          status = new MultiStatus(pluginID, 0, Messages.PropertyField_authenticatedField_status_invalidPassword, null);
          status.addAll(finalStatus);
          break;
        }
      }

      ErrorDialog.openError(getHelper().getShell(), Messages.PropertyField_authenticatedField_authenticationStatus, null, status);
    }
  }

  public void dispose()
  {
    Label label = getLabel();
    if (label != null)
    {
      label.dispose();
    }

    Control mainControl = getMainControl();
    if (mainControl != null)
    {
      mainControl.dispose();
    }

    Control mainHelper = getMainHelper();
    if (mainHelper != null)
    {
      mainHelper.dispose();
    }
  }
}

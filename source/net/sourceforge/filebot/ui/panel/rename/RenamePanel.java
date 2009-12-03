
package net.sourceforge.filebot.ui.panel.rename;


import static net.sourceforge.filebot.Settings.*;
import static net.sourceforge.tuned.ui.LoadingOverlayPane.*;
import static net.sourceforge.tuned.ui.TunedUtilities.*;

import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import ca.odell.glazedlists.ListSelection;
import ca.odell.glazedlists.swing.EventSelectionModel;

import net.miginfocom.swing.MigLayout;
import net.sourceforge.filebot.ResourceManager;
import net.sourceforge.filebot.Settings;
import net.sourceforge.filebot.similarity.Match;
import net.sourceforge.filebot.ui.panel.rename.RenameModel.FormattedFuture;
import net.sourceforge.filebot.web.AnidbClient;
import net.sourceforge.filebot.web.Episode;
import net.sourceforge.filebot.web.EpisodeListProvider;
import net.sourceforge.filebot.web.IMDbClient;
import net.sourceforge.filebot.web.MovieDescriptor;
import net.sourceforge.filebot.web.TMDbClient;
import net.sourceforge.filebot.web.TVDotComClient;
import net.sourceforge.filebot.web.TVRageClient;
import net.sourceforge.filebot.web.TheTVDBClient;
import net.sourceforge.tuned.ExceptionUtilities;
import net.sourceforge.tuned.PreferencesMap.PreferencesEntry;
import net.sourceforge.tuned.ui.ActionPopup;
import net.sourceforge.tuned.ui.LoadingOverlayPane;


public class RenamePanel extends JComponent {
	
	protected final RenameModel renameModel = new RenameModel();
	
	protected final RenameList<FormattedFuture> namesList = new RenameList<FormattedFuture>(renameModel.names());
	
	protected final RenameList<File> filesList = new RenameList<File>(renameModel.files());
	
	protected final MatchAction matchAction = new MatchAction(renameModel);
	
	protected final RenameAction renameAction = new RenameAction(renameModel);
	
	private static final PreferencesEntry<String> persistentPreserveExtension = Settings.forPackage(RenamePanel.class).entry("rename.extension.preserve").defaultValue("true");
	private static final PreferencesEntry<String> persistentFormatExpression = Settings.forPackage(RenamePanel.class).entry("rename.format");
	

	public RenamePanel() {
		namesList.setTitle("New Names");
		namesList.setTransferablePolicy(new NamesListTransferablePolicy(renameModel.values()));
		
		filesList.setTitle("Original Files");
		filesList.setTransferablePolicy(new FilesListTransferablePolicy(renameModel.files()));
		
		// restore state
		renameModel.setPreserveExtension(Boolean.parseBoolean(persistentPreserveExtension.getValue()));
		
		// filename formatter
		renameModel.useFormatter(File.class, new FileNameFormatter(renameModel.preserveExtension()));
		
		// movie formatter
		renameModel.useFormatter(MovieDescriptor.class, new MovieFormatter());
		
		try {
			// restore custom episode formatter
			renameModel.useFormatter(Episode.class, new EpisodeExpressionFormatter(persistentFormatExpression.getValue()));
		} catch (Exception e) {
			// illegal format, ignore
		}
		
		RenameListCellRenderer cellrenderer = new RenameListCellRenderer(renameModel);
		
		namesList.getListComponent().setCellRenderer(cellrenderer);
		filesList.getListComponent().setCellRenderer(cellrenderer);
		
		EventSelectionModel<Match<Object, File>> selectionModel = new EventSelectionModel<Match<Object, File>>(renameModel.matches());
		selectionModel.setSelectionMode(ListSelection.SINGLE_SELECTION);
		
		// use the same selection model for both lists to synchronize selection
		namesList.getListComponent().setSelectionModel(selectionModel);
		filesList.getListComponent().setSelectionModel(selectionModel);
		
		// synchronize viewports
		new ScrollPaneSynchronizer(namesList, filesList);
		
		// create Match button
		JButton matchButton = new JButton(matchAction);
		matchButton.setVerticalTextPosition(SwingConstants.BOTTOM);
		matchButton.setHorizontalTextPosition(SwingConstants.CENTER);
		
		// create Rename button
		JButton renameButton = new JButton(renameAction);
		renameButton.setVerticalTextPosition(SwingConstants.BOTTOM);
		renameButton.setHorizontalTextPosition(SwingConstants.CENTER);
		
		// create fetch popup
		ActionPopup fetchPopup = createFetchPopup();
		
		namesList.getListComponent().setComponentPopupMenu(fetchPopup);
		matchButton.setComponentPopupMenu(fetchPopup);
		matchButton.addActionListener(showPopupAction);
		
		// create settings popup
		renameButton.setComponentPopupMenu(createSettingsPopup());
		
		setLayout(new MigLayout("fill, insets dialog, gapx 10px", "[fill][align center, pref!][fill]", "align 33%"));
		
		add(filesList, "grow, sizegroupx list");
		
		// make buttons larger
		matchButton.setMargin(new Insets(3, 14, 2, 14));
		renameButton.setMargin(new Insets(6, 11, 2, 11));
		
		add(matchButton, "split 2, flowy, sizegroupx button");
		add(renameButton, "gapy 30px, sizegroupx button");
		
		add(new LoadingOverlayPane(namesList, namesList, "32px", "30px"), "grow, sizegroupx list");
	}
	

	protected ActionPopup createFetchPopup() {
		final ActionPopup actionPopup = new ActionPopup("Fetch Episode List", ResourceManager.getIcon("action.fetch"));
		
		// create actions for match popup episode list completion
		for (EpisodeListProvider provider : Arrays.asList(new TVRageClient(), new AnidbClient(), new TVDotComClient(), new IMDbClient(), new TheTVDBClient(getApplicationProperty("thetvdb.apikey")))) {
			actionPopup.add(new AutoCompleteAction(provider.getName(), provider.getIcon(), new EpisodeListMatcher(provider)));
		}
		
		actionPopup.addSeparator();
		actionPopup.addDescription(new JLabel("Movie Mode:"));
		
		// create action for movie name completion
		TMDbClient tmdb = new TMDbClient(getApplicationProperty("themoviedb.apikey"));
		actionPopup.add(new AutoCompleteAction(tmdb.getName(), tmdb.getIcon(), new MovieHashMatcher(tmdb)));
		
		actionPopup.addSeparator();
		actionPopup.addDescription(new JLabel("Options:"));
		
		actionPopup.add(new AbstractAction("Edit Format", ResourceManager.getIcon("action.format")) {
			
			@Override
			public void actionPerformed(ActionEvent evt) {
				EpisodeFormatDialog dialog = new EpisodeFormatDialog(SwingUtilities.getWindowAncestor(RenamePanel.this));
				dialog.setLocation(getOffsetLocation(dialog.getOwner()));
				dialog.setVisible(true);
				
				switch (dialog.getSelectedOption()) {
					case APPROVE:
						renameModel.useFormatter(Episode.class, new EpisodeExpressionFormatter(dialog.getSelectedFormat().getExpression()));
						persistentFormatExpression.setValue(dialog.getSelectedFormat().getExpression());
						break;
					case USE_DEFAULT:
						renameModel.useFormatter(Episode.class, null);
						persistentFormatExpression.remove();
						break;
				}
			}
		});
		
		return actionPopup;
	}
	

	protected ActionPopup createSettingsPopup() {
		ActionPopup actionPopup = new ActionPopup("Rename Options", ResourceManager.getIcon("action.rename.small"));
		
		actionPopup.addDescription(new JLabel("Extension:"));
		
		actionPopup.add(new PreserveExtensionAction(true, "Preserve", ResourceManager.getIcon("action.extension.preserve")));
		actionPopup.add(new PreserveExtensionAction(false, "Override", ResourceManager.getIcon("action.extension.override")));
		
		actionPopup.addSeparator();
		actionPopup.addDescription(new JLabel("History:"));
		
		actionPopup.add(new AbstractAction("Open History", ResourceManager.getIcon("action.report")) {
			
			@Override
			public void actionPerformed(ActionEvent e) {
				History model = HistorySpooler.getInstance().getCompleteHistory();
				
				HistoryDialog dialog = new HistoryDialog(getWindow(RenamePanel.this));
				dialog.setLocationRelativeTo(RenamePanel.this);
				dialog.setModel(model);
				
				// show and block
				dialog.setVisible(true);
				
				if (!model.equals(dialog.getModel())) {
					// model was changed, switch to the new model
					HistorySpooler.getInstance().commit(dialog.getModel());
				}
			}
		});
		
		return actionPopup;
	}
	

	protected final Action showPopupAction = new AbstractAction("Show Popup") {
		
		@Override
		public void actionPerformed(ActionEvent e) {
			// show popup on actionPerformed only when names list is empty
			if (renameModel.size() > 0 && !renameModel.hasComplement(0)) {
				JComponent source = (JComponent) e.getSource();
				
				// display popup below component
				source.getComponentPopupMenu().show(source, -3, source.getHeight() + 4);
			}
		}
	};
	

	protected class PreserveExtensionAction extends AbstractAction {
		
		private final boolean activate;
		

		private PreserveExtensionAction(boolean activate, String name, Icon icon) {
			super(name, icon);
			this.activate = activate;
		}
		

		@Override
		public void actionPerformed(ActionEvent evt) {
			renameModel.setPreserveExtension(activate);
			
			// use different file name formatter
			renameModel.useFormatter(File.class, new FileNameFormatter(renameModel.preserveExtension()));
			
			// display changed state
			filesList.repaint();
			
			// save state
			persistentPreserveExtension.setValue(Boolean.toString(activate));
		}
	}
	

	protected class AutoCompleteAction extends AbstractAction {
		
		private final AutoCompleteMatcher matcher;
		

		public AutoCompleteAction(String name, Icon icon, AutoCompleteMatcher matcher) {
			super(name, icon);
			
			this.matcher = matcher;
			
			// disable action while episode list matcher is working
			namesList.addPropertyChangeListener(LOADING_PROPERTY, new PropertyChangeListener() {
				
				@Override
				public void propertyChange(PropertyChangeEvent evt) {
					// disable action while loading is in progress
					setEnabled(!(Boolean) evt.getNewValue());
				}
			});
		}
		

		@Override
		public void actionPerformed(ActionEvent evt) {
			// auto-match in progress
			namesList.firePropertyChange(LOADING_PROPERTY, false, true);
			
			// clear names list
			renameModel.values().clear();
			
			SwingWorker<List<Match<File, ?>>, Void> worker = new SwingWorker<List<Match<File, ?>>, Void>() {
				
				private final List<File> remainingFiles = new LinkedList<File>(renameModel.files());
				

				@Override
				protected List<Match<File, ?>> doInBackground() throws Exception {
					List<Match<File, ?>> matches = matcher.match(remainingFiles);
					
					// remove matched files
					for (Match<File, ?> match : matches) {
						remainingFiles.remove(match.getValue());
					}
					
					return matches;
				}
				

				@Override
				protected void done() {
					try {
						List<Match<Object, File>> matches = new ArrayList<Match<Object, File>>();
						
						for (Match<File, ?> match : get()) {
							matches.add(new Match<Object, File>(match.getCandidate(), match.getValue()));
						}
						
						renameModel.clear();
						renameModel.addAll(matches);
						
						// add remaining file entries
						renameModel.files().addAll(remainingFiles);
					} catch (Exception e) {
						Logger.getLogger("ui").log(Level.WARNING, ExceptionUtilities.getRootCauseMessage(e), e);
					} finally {
						// auto-match finished
						namesList.firePropertyChange(LOADING_PROPERTY, true, false);
					}
				}
			};
			
			worker.execute();
		}
	}
	
}

package io.github.leonidius20.recorder.ui.recordings_list.view

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import io.github.leonidius20.recorder.R
import io.github.leonidius20.recorder.data.playback.PlaybackService
import io.github.leonidius20.recorder.databinding.FragmentRecordingsListBinding
import io.github.leonidius20.recorder.ui.common.RecStudioFragment
import io.github.leonidius20.recorder.ui.recordings_list.viewmodel.RecordingsListViewModel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map

@AndroidEntryPoint
class RecordingsListFragment : RecStudioFragment() {

    private var _binding: FragmentRecordingsListBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private val viewModel: RecordingsListViewModel by viewModels()

    private lateinit var adapter: RecordingsListAdapter

    private lateinit var trashRecordingsIntentLauncher: ActivityResultLauncher<IntentSenderRequest>

    private lateinit var deleteRecordingsIntentLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordingsListBinding.inflate(inflater, container, false)
        val root: View = binding.root

        binding.recordingList.setHasFixedSize(true) // supposedly improves performance


        val onItemClick: (Int) -> Unit = { position: Int ->
            if (actionMode != null) {
                viewModel.toggleSelection(position)
            } else {
                // start playback
                setPlayingFile(position)
            }
        }

        val onItemLongClick = { position: Int ->
            if (actionMode == null) {
                actionMode = requireActivity().startActionMode(actionModeCallback)
            }

            viewModel.toggleSelection(position)
        }

        adapter = RecordingsListAdapter(
            requireContext(),
            onItemClick,
            onItemLongClick
        )
        binding.recordingList.adapter = adapter

        viewModel.state.collectSinceStarted { state ->

            adapter.setData(ArrayList(state.recordings))
            //binding.recordingList.scrollToPosition(0)

            binding.emptyListText.isVisible = state.recordings.isEmpty()
        }

        viewModel.state.collectDistinctSinceStarted({ it.numItemsSelected }) { numItemsSelected ->
            val shouldShowActionMode = numItemsSelected > 0

            // if should show actionMode, but it is not being shown yet
            if (shouldShowActionMode && actionMode == null) {
                actionMode = requireActivity().startActionMode(actionModeCallback)
            } else if (!shouldShowActionMode && actionMode != null) {
                // if should not show action mode but it is being shown
                actionMode!!.finish()
                actionMode = null
            }

            if (shouldShowActionMode) {
                actionMode!!.apply {
                    title = getString(R.string.recs_list_action_mode_num_selected, numItemsSelected)
                    invalidate()
                }
            }
        }

        trashRecordingsIntentLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    // nothing
                } else {
                    Toast.makeText(requireContext(), "failure", Toast.LENGTH_SHORT).show()
                }
                actionMode?.finish()
            }

        deleteRecordingsIntentLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    // nothing
                } else {
                    Toast.makeText(requireContext(), "failure", Toast.LENGTH_SHORT).show()
                }
                actionMode?.finish()
            }


        // registerForContextMenu(binding.recordingList)

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    var actionMode: ActionMode? = null

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {

            if (viewModel.state.value.numItemsSelected > 1) {
                mode.menuInflater.inflate(
                    R.menu.recordings_list_multiple_recordings_context_menu,
                    menu
                )
            } else {
                mode.menuInflater.inflate(R.menu.recordings_list_one_recording_context_menu, menu)
            }

            // todo: this is temporary, remove once sharing is implemented
            menu.removeItem(R.id.recordings_list_action_share)

            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            // todo: invalidation happends on each toggling of selection
            // so we can add or remove menu elements here based on if it is
            // 1 element selected or multiple
            menu.clear()
            if (viewModel.state.value.numItemsSelected > 1) {
                mode.menuInflater.inflate(
                    R.menu.recordings_list_multiple_recordings_context_menu,
                    menu
                )
            } else {
                mode.menuInflater.inflate(R.menu.recordings_list_one_recording_context_menu, menu)
            }

            // todo: this is temporary, remove once sharing is implemented
            menu.removeItem(R.id.recordings_list_action_share)

            return true
        }

        @SuppressLint("NewApi") // the "trash" option requires api 30 but it isn't shown in the menu on lower apis
        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.recordings_list_action_rename -> {
                    rename()
                }

                R.id.recordings_list_action_delete_forever -> {
                    delete()
                }

                R.id.recordings_list_action_share -> {
                    // todo
                }

                R.id.recordings_list_action_trash -> {
                    trash()
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            viewModel.clearSelection()
            actionMode = null
        }


    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun trash() {
        val intent = viewModel.requestTrashingSelected()
        trashRecordingsIntentLauncher.launch(
            IntentSenderRequest.Builder(intent).build()
        )
    }

    fun delete() {
        //val positions = adapter.getSelectedItemsPositions()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = viewModel.requestDeletingSelected()
            deleteRecordingsIntentLauncher.launch(
                IntentSenderRequest.Builder(intent).build()
            )
        } else {
            // todo: dialogFragment


            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Deleting files")
                .setMessage("Do you confirm deleting ${viewModel.state.value.numItemsSelected} selected file(s)?")
                .setPositiveButton(android.R.string.yes) { _, _ ->
                    viewModel.legacyDeleteSelectedWithoutConfirmation()
                    actionMode?.finish()
                }
                .setNegativeButton(android.R.string.no) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

    }

    fun rename() {
        val selectedItem = viewModel.getFirstSelectedItem()

        actionMode?.finish()

        findNavController().navigate(
            RecordingsListFragmentDirections.actionNavigationRecordingsListToRenameDialogFragment(
                fileToRename = selectedItem.uri,
                currentFileName = selectedItem.name,
                id = selectedItem.id,
            )
        )
    }

    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null


    override fun onStart() {
        super.onStart()
        val context = requireContext()
        val sessionToken =
            SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val factory = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = factory
        factory.addListener({
            mediaController = factory.let {
                if (it.isDone)
                    it.get()
                else
                    null
            }

            binding.playerView.player = mediaController

            viewModel.state
                .distinctUntilChangedBy { it.itemIds }
                .map { it.recordings }
                .collectSinceStarted { recordings ->

                    mediaController?.replaceMediaItems(0, mediaController!!.mediaItemCount,
                        recordings.map { recording ->
                            MediaItem.Builder()
                                .setUri(recording.uri)
                                .setMediaId(recording.id.toString())
                                .setMediaMetadata(
                                    MediaMetadata.Builder().setDisplayTitle(recording.name).build()
                                ).build()
                        }
                    )
                }

            mediaController?.addListener(object : Player.Listener {

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    adapter.setPlaying(mediaController!!.currentMediaItemIndex)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        adapter.setPlaying(mediaController!!.currentMediaItemIndex)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        adapter.resetPlayingItemHighlighting()
                    }
                }

            })

            mediaController?.prepare()


        }, MoreExecutors.directExecutor())
    }
    //todo: replace with lifecycle aware component

    override fun onStop() {
        super.onStop()
        MediaController.releaseFuture(controllerFuture!!)
        controllerFuture = null
        mediaController = null
    }


    private fun setPlayingFile(position: Int) {
        with(mediaController!!) {
            seekTo(position, 0L)
            if (!isPlaying) play()
        }
    }

}